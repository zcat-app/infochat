---
id: M1-513
title: "Revisit D49: single local LLM runtime in prod (retire Ollama-embeddings shape b)"
status: abandoned
created: 2026-06-29
last_updated: 2026-07-17
blocked_by: []
abandoned_reason: superseded
files_budget: 5
files_scope:
  - docs/spec/decisions.md
  - docs/design/07-deployment.md
  - docker-compose.yml
  - docs/spec/deployment.md
complexity: medium
risk: medium
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The dev inner-loop Ollama. D49 ties the `ollama` Compose service's `dev`
    profile to the developer's quarkus:dev loop at localhost:11434; that stays.
    This ticket only retires Ollama as a PROD embedder running alongside the
    generative llama.cpp (the `ollama` service's NON-dev / prod usage in shape b).
  - >-
    Pure-llama.cpp shape (a) — second llama-server in --embeddings mode. That is
    the kept, canonical local embeddings path and is unchanged.
  - >-
    The remote provider path (remote-llm profile, "one local + one remote"). Already
    permitted; this ticket does not touch it and in fact the new rule preserves it
    explicitly ("at most one LOCAL runtime, optionally + one remote").
  - >-
    Re-embedding mechanics for an existing shape-(b) deployment that migrates to
    shape (a). The re-embed itself is the M1-428 ops script; this ticket only
    documents WHEN a re-embed is mandatory (a backend swap that changes the embedding
    numeric space), it does not re-implement re-embedding.
  - >-
    The embedding model identity / dimension (nomic-class, 768-dim,
    allow-model-change=false) and the EmbeddingMetadataStartupGuard. Unchanged.
  - "DB schema / migrations — none."
acceptance:
  - >-
    A new decision is recorded in docs/spec/decisions.md that SUPERSEDES D49's
    shape-(b) relaxation: the prod host runs AT MOST ONE local LLM runtime
    technology (llama.cpp), optionally augmented by ONE remote provider. The two
    permitted local prod shapes become: (1) pure llama.cpp — generative llama-server
    + a second llama-server in --embeddings mode (D49 shape a, kept); (2) llama.cpp
    generative local + remote provider for the other role. D49 shape (b)
    (generative llama.cpp + Ollama embeddings, two local runtimes) is RETIRED. The
    new decision links back to D49 and states D49 is superseded on this point only
    (its "llama.cpp is a real standalone backend" substance stands).
  - >-
    The rationale is recorded with both drivers: (a) operational — two local runtime
    technologies double the model-resident RAM and supervision surface on a small
    host (the 2026-06-28 incident lesson; see M1-512); (b) determinism — Ollama's
    nomic-embed-text and llama.cpp's nomic GGUF are not guaranteed to produce
    bit-identical vectors (quantization/pooling/normalization differ by runtime), and
    EmbeddingMetadataStartupGuard pins model NAME + dimension, NOT numeric
    equivalence, so a fleet that mixes or migrates backends can silently write
    pgvector rows that are incomparable with existing ones — corrupting deterministic
    retrieval with no error and no re-embed trigger.
  - >-
    docker-compose.yml no longer documents/supports the "llama.cpp + Ollama
    embeddings" prod profile combo (the `--profile llamacpp --profile ollama` shape).
    The `ollama` service retains ONLY its `dev` profile. The comment block at
    docker-compose.yml ~L171-190 is updated to list the two kept shapes and to state
    shape (b) is retired (ref this ticket + the new decision id).
  - >-
    docs/design/07-deployment.md §7.7 drops shape (b) from the LLM serve-wiring
    options, keeps shapes (a) and local+remote, and adds the one-time migration note
    (a deployment currently on shape b must move to shape a or local+remote and
    re-embed via M1-428 before trusting similarity results, because the embedding
    space may shift).
  - >-
    docs/spec/deployment.md §Deployment scenarios reflects the tightened rule at spec
    altitude ("at most one local LLM runtime, optionally one remote"). No new operator
    INPUT and no wizard-contract change (the wizard simply stops offering the
    shape-b combo).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
notes: >-
  Origin: 2026-06-28 VPS resource-exhaustion investigation. The running prod
  deployment is already shape (a) (pure llama.cpp); the only Ollama on the box was an
  ORPHANED host dev systemd service (idle, no model) — torn down operationally and
  documented in M1-512. This ticket addresses the DESIGN question that surfaced:
  whether the D49 shape-(b) relaxation (two local runtimes) should exist at all now
  that shape (a) gives llama.cpp operators embeddings without a second runtime. The
  recommendation (and this ticket's direction) is to retire shape (b). Because this
  amends a recorded decision (D49), the superseding decision must be ratified when the
  ticket is started — the acceptance encodes the proposed outcome, not a fait
  accompli. Pairs with M1-512 (the OS/container resource harness); M1-512 is the
  runtime-envelope fix, M1-513 is the topology-simplification.
spec_refs:
  - "docs/spec/decisions.md D49"
  - "docs/design/07-deployment.md §7.7 (Compose profiles / LLM serve wiring)"
  - "docs/spec/llm.md (embeddings determinism boundary)"
decision_refs:
  - D49
reviews: []
overrides: []
escalations: []
---

## Context

The 2026-06-28 VPS resource-exhaustion forensics confirmed the running prod
deployment is D49 **shape (a)** — pure llama.cpp (generative `llama-server` +
a second `llama-server` in `--embeddings` mode). The only Ollama process on the host
was an **orphaned dev systemd service** (idle, no model loaded), reaped operationally
under M1-512.

That surfaced a design question: D49 **shape (b)** lets an operator run Ollama as the
embedder *alongside* the generative llama.cpp — two local LLM runtimes at once. D49
justified the relaxation because "one `llama-server` serves one model, so it can't
also be the embedder." But shape (a) **already** solves that with a second
`llama-server`, so shape (b)'s only remaining benefit is provisioning convenience
(`ollama pull`), while its costs are real:

- **Operational** — two local runtime technologies double the resident-model RAM and
  supervision surface on a small host (exactly the oversubscription that aggravated
  the incident; see M1-512).
- **Determinism** — Ollama's `nomic-embed-text` and the llama.cpp nomic GGUF are not
  guaranteed bit-identical (quantization / pooling / normalization differ by runtime).
  `EmbeddingMetadataStartupGuard` pins model *name + dimension*, not numeric
  equivalence, so mixing or migrating embedding backends can silently write pgvector
  rows incomparable with existing ones — degrading deterministic retrieval with no
  error.

## Proposal

Tighten the rule to **"at most one LOCAL LLM runtime, optionally augmented by one
remote provider"** and **retire D49 shape (b)**. Keep shape (a) (pure llama.cpp) and
local+remote. Record a superseding decision; the dev inner-loop Ollama is untouched.

Decision ratification is required at start (this amends D49). See acceptance.

## Abandoned (2026-07-17)

Superseded — the ticket's premise and remedy are overtaken by decisions
ratified after it was drafted, verified against `main` in the 2026-07-17
session:

- **Premise stale.** It assumes prod runs D49 shape (a) (pure llama.cpp).
  Prod pivoted to the `remote-llm` profile — remote DeepSeek generation +
  LOCAL Ollama embeddings — via **M1-529/D54** (2026-07-01) and
  **M1-603/604/D57** (2026-07-11). Committed `prod/runtime/application.properties`:
  generation → `api.deepseek.com`, embeddings → `http://ollama:11434/v1`.
- **Remedy collides with D54 (done).** D54 mandates embeddings ALWAYS local,
  NEVER remote, and explicitly REUSES the D49 Ollama-embeddings shape for the
  remote-llm embedder. So acceptance item 3 (strip the prod `ollama` profile)
  would STRAND live prod's embedder, and the proposed replacement rule
  ("generative local + remote provider for the other role") would permit
  remote embeddings — which D54 forbids — and omits the topology prod
  actually runs.
- **Operational driver already handled.** The two-local-runtimes → RAM
  concern was addressed independently by **M1-512** (per-container caps,
  done).
- **Determinism concern is real but mis-homed.** `EmbeddingMetadataStartupGuard`
  pins model name + dimension, not vector-space equivalence — a genuine
  latent gap, but a CODE/guard matter, not this docs edit. It is largely
  fenced by D54 (one local embedder, `allow-model-change=false`) plus the
  M1-428 re-embed procedure; any further hardening is a fresh, low-priority
  code ticket.

Integrating as-written would break live prod and contradict D54, so this is
closed rather than started.
