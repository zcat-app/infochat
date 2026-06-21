---
id: M1-417
title: Make llama.cpp backend functional; operator-chosen embeddings
status: pending
created: 2026-06-21
last_updated: 2026-06-21
blocked_by: []
files_budget: 12
files_scope:
  - docker-compose.yml
  - prod/scripts/4-llm.sh
  - docs/spec/deployment.md
  - docs/spec/decisions.md
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
  - infochat-llm-adapter/src/test/java/**
  - prod/scripts/**
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  # The per-task LLM switcher is M1-418 (depends on this ticket); do NOT build
  # any task-routing CLI here. This ticket only makes the llama.cpp BACKEND
  # functional + correct.
  - prod/switch-llm.sh
  # No app-code (Java main) changes: OpenAiCompatibleProvider already speaks the
  # OpenAI-compatible protocol to llama.cpp; the gap is compose/wizard wiring +
  # config generation, not the adapter.
  - infochat-collector/src/main/java/**
  - infochat-provider/src/main/java/**
  - infochat-llm-adapter/src/main/java/**
acceptance:
  - The compose generative `llamacpp` service loads the operator-chosen GGUF
    (via `LLAMA_ARG_MODEL`/equivalent) and binds `0.0.0.0`
    (via `LLAMA_ARG_HOST`/equivalent), so `llama-server` serves the
    OpenAI-compatible API at `llamacpp:8080` over the compose network, with a
    healthcheck.
  - The `4-llm.sh` llamacpp branch injects the chosen GGUF filename into
    that wiring (not merely downloads it into the volume).
  - The llamacpp branch prompts for an embeddings backend and wires both shapes.
  - llama.cpp embeddings shape — a second compose service (nomic-class GGUF in
    embeddings mode, own port, own healthcheck) that infochat.embeddings.*
    points at, with no Ollama running.
  - Ollama embeddings shape — the ollama service runs alongside llamacpp and
    infochat.embeddings.* points at the Ollama nomic endpoint.
  - In BOTH shapes `infochat.embeddings.dimension=768` and the nomic-class model
    are preserved; embeddings never point at the generative GGUF. The prior
    embeddings-on-generative-GGUF behavior is removed.
  - The llamacpp branch DEFAULTS to predefined checksum-pinned GGUFs (gemma
    generative + nomic embeddings; predefined SHA-256 enforced, not skippable)
    and lets the operator override each with a custom URL (a custom override
    keeps the existing optional-SHA prompt). A generative override is free; an
    embeddings override MUST stay 768-dim (allow-model-change=false) — the wizard
    warns/guards rather than silently accepting a mismatched-dimension embedder.
  - A new automated test pins the wiring for both embeddings shapes — generative
    model+host args present; embeddings resolve to the llama.cpp embeddings
    service OR Ollama as chosen, never the generative GGUF.
  - Both `docs/spec/deployment.md` and `docs/spec/decisions.md` record, as a new
    decision ID, that (a) llama.cpp is a real standalone backend served by one
    instance per model, and (b) in the Ollama-embeddings shape the two LLM
    services MAY run together (relaxing "operator picks ONE local backend");
    `docs/design/07-deployment.md` reflects both shapes.
  - The full `mvn verify` is green from the repo root.
test_plan:
  adds:
    # - infochat-llm-adapter/src/test/java/.../LlamacppWiringTest.java  (final path TBD by plan-writer)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/deployment.md §Deployment scenarios
decision_refs:
  # - D<NN> minted by this ticket (llama.cpp standalone backend; per-model
  #   instances; Ollama-embeddings co-run allowance)

reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-417: Make llama.cpp backend functional; operator-chosen embeddings

## Context

The `llamacpp` LLM backend is declared but not functional. The compose
`llamacpp` service (docker-compose.yml §LLM backend services) passes the
`llama-server` image NO model path (`-m`/`LLAMA_ARG_MODEL`) and NO host bind
(`--host 0.0.0.0`/`LLAMA_ARG_HOST`), and `4-llm.sh` only downloads the GGUF into
the model-cache volume — it never tells the server which file to load.
`llama-server` therefore exits (no model) and, even if started, would bind
container-loopback and be unreachable as `llamacpp:8080`. No test exercises the
service, so the gap was never caught. Ollama's bare stanza works only because
its image defaults to `0.0.0.0:11434` and serves models dynamically by name;
llama.cpp fundamentally needs explicit model + host args.

Separately, a single llama.cpp server serves one model, so it cannot be both the
generative model and the fixed 768-dim nomic embedder
(`infochat.embeddings.dimension=768`, `allow-model-change=false`) — yet
`4-llm.sh`'s llamacpp branch points `infochat.embeddings.*` at the generative
GGUF, which is broken-by-construction. This ticket makes llama.cpp serve a chosen
generative model AND offers the operator two embeddings shapes: a SECOND
llama.cpp instance (nomic GGUF, `--embeddings`) for a pure-llama.cpp deployment,
or the proven Ollama nomic embedder running alongside. This unblocks M1-418 (the
per-task LLM switcher), which needs llama.cpp to actually serve before it can
route tasks to it.

## Acceptance

- **Generative wiring.** The compose generative `llamacpp` service loads the
  chosen GGUF and binds `0.0.0.0`, serving the OpenAI-compatible API at
  `llamacpp:8080` over the compose network, with a healthcheck. `4-llm.sh`
  injects the chosen GGUF into that wiring.
- **Embeddings, operator-chosen — both shapes work:**
  - *llama.cpp embeddings:* a second compose service (nomic GGUF, `--embeddings`,
    own port + healthcheck); `infochat.embeddings.*` points at it; no Ollama.
  - *Ollama embeddings:* `ollama` runs alongside `llamacpp`; `infochat.embeddings.*`
    points at the Ollama nomic endpoint.
  - Both preserve 768-dim + the nomic-class model; neither points embeddings at
    the generative GGUF.
- **Predefined GGUFs (default + override).** The llamacpp branch defaults to the
  pinned gemma (generative) and nomic (embeddings) GGUFs — Enter accepts them,
  SHA-256 enforced — and the operator may override each with a custom URL (custom
  keeps the optional-SHA prompt). Generative override is unrestricted; an
  embeddings override must remain 768-dim (the wizard guards/warns, consistent
  with `allow-model-change=false`).
- **Wiring test.** A new automated test pins both embeddings shapes.
- **Spec/decision.** `deployment.md` + `decisions.md` mint a decision ID for the
  standalone-llama.cpp model + the Ollama-co-run allowance; `07-deployment.md`
  reflects both shapes.
- `mvn verify` is green from the repo root.

## Out-of-scope

This ticket makes the llama.cpp *backend* serve correctly; it does NOT build the
per-task switcher (`prod/switch-llm.sh` — that is M1-418, `blocked_by` this
ticket). No Java main-code changes: `OpenAiCompatibleProvider` already speaks the
OpenAI-compatible protocol to llama.cpp; the defect is in compose wiring + the
wizard's config generation. Leave the remote and Ollama-only (non-llamacpp)
branches of `4-llm.sh` unchanged except for the minimal hook that starts Ollama
for embeddings when the operator picks the Ollama-embeddings shape.

## Notes

- **Decision (M1-417 settles this):** support BOTH embeddings shapes, operator
  picks. This is the most flexible and the most expensive option — it inherits
  the second-instance wiring AND the Ollama-co-run spec amendment. Chosen
  deliberately over X-only (pure llama.cpp) or Y-only (Ollama-for-embeddings).
- **Plan-writer (complexity:high):** this ticket is large (generative wiring +
  TWO embeddings paths + two predefined GGUFs + spec amend + tests). The sidecar
  should (a) settle the model/host/port injection mechanism (compose `command:`
  vs `environment: LLAMA_ARG_*`, GGUF name flowing via `secrets.env`/env like the
  M1-391 adapter data-dirs), (b) settle the test approach — a real-server CI
  smoke needs a model and may be too heavy for `mvn verify`, so pinning the
  generated wiring/config is the achievable automated check with the real-server
  smoke folded into the VPS manual test plan, and (c) decide whether to
  DECOMPOSE into an umbrella + subtickets (e.g. generative-wiring vs
  embeddings-options) if `files_budget` is at risk.
- **Predefined GGUFs (resolved + checksummed 2026-06-21).** Pin these as the
  curated entries; the SHA-256 is verified from each file's HF git-LFS pointer
  and MUST be enforced (not skippable):
  - Generative (chat/summarizer/etc.): `gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf`
    - URL: `https://huggingface.co/unsloth/gemma-4-E4B-it-qat-GGUF/resolve/main/gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf`
    - SHA-256: `b3052f962d6449b4eb2075733c068bdec1c51eadb7b237e6c3157bfbb7b1dae0` (size 4215693760, ~4.2 GB)
    - QAT (quant-aware-trained) Q4, so Q4-size keeps near-BF16 quality. The repo's
      `MTP/*` and `mmproj-*` variants are NOT used (speculative-decoding /
      multimodal-projector — irrelevant to v1 text generation).
  - Embeddings (llama.cpp embeddings shape): `nomic-embed-text-v1.5.f16.gguf`
    - URL: `https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.f16.gguf`
    - SHA-256: `f7af6f66802f4df86eda10fe9bbcfc75c39562bed48ef6ace719a251cf1c2fdb` (size 274290560, ~274 MB)
    - F16 (not a low-bit quant): embeddings are tiny and quantization degrades
      vector fidelity; dimension stays 768 either way. This is `nomic-embed-text-v1.5`
      — the same model family as the Ollama `nomic-embed-text` the rest of the
      fleet uses, so vectors are cross-deployment compatible.
- Adjacent code/patterns: GGUF download + SHA-256 verify already exist in
  `4-llm.sh:175-230` (M1-394 checksum precedent); env-via-secrets.env passthrough
  is M1-389/M1-391 (adapter data-dirs).
- `security_relevant`: the new `0.0.0.0` binds + the two llama.cpp surfaces and
  the GGUF integrity path warrant `/redteam` — confirm the binds stay within the
  compose network (no host publish) and predefined checksums cannot be skipped.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-417-*.md
```
