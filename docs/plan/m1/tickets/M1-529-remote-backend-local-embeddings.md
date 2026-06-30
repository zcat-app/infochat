---
id: M1-529
title: "Remote LLM backend mis-configures embeddings (model left at nomic against a remote endpoint)"
status: pending
created: 2026-06-30
last_updated: 2026-06-30
blocked_by: []
files_budget: 5
files_scope:
  - prod/scripts/4-llm.sh
  - docs/spec/decisions.md
  - docs/design/05-llm-and-embeddings.md
  - SETUP_GUIDE.md
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "Changing the embedding model/dimension contract itself (nomic-embed-text / 768 / allow-model-change=false). The frozen-embedding invariant and EmbeddingMetadataStartupGuard stay exactly as they are; this ticket changes only WHERE the fixed nomic embedder is hosted for the remote-chat case, never WHICH model embeds."
  - "The ollama and llamacpp branches of 4-llm.sh — they already host embeddings locally (same ollama instance, or the llamacpp-embeddings second instance). Untouched."
  - "switch-llm.sh — it already never touches embeddings; that contract is correct and out of scope."
  - "Any pgvector / schema / re-embed work."
acceptance:
  - >-
    A decision is recorded in docs/spec/decisions.md (a new D-number) stating
    that embeddings ALWAYS run on a local nomic-768 backend and are NEVER routed
    to a remote provider, with the rationale: the embedding model is frozen for
    the deployment's life (allow-model-change=false; pgvector column is
    dimension-fixed; cross-provider vectors are incomparable), and commercial
    OpenAI-compatible endpoints do not reliably serve a model named
    nomic-embed-text at 768-dim. grep -nE 'embeddings.*local|local.*embeddings'
    docs/spec/decisions.md returns at least one match on the new decision row.
  - >-
    The remote branch of prod/scripts/4-llm.sh NO LONGER points
    infochat.embeddings.base-url at the operator's remote endpoint. Instead it
    provisions a local nomic embedder by reusing the llamacpp-branch
    ollama-embeddings pattern: start the ollama compose service, poll readiness,
    `ollama pull nomic-embed-text`, then set infochat.embeddings.base-url to the
    ollama service URL (http://ollama:11434/v1) and infochat.embeddings.model to
    nomic-embed-text. After a remote-backend run, the generated
    application.properties has infochat.embeddings.base-url pointing at the
    ollama URL, NOT the remote base-url the operator typed.
  - >-
    The remote branch still routes the six GENERATIVE tasks
    (security/tagger/entity/summarizer/chat/translator) to the remote endpoint
    with the API key (unchanged from today); only embeddings move local. A grep
    of the generated config shows infochat.llm.chat.base-url == the remote URL
    AND infochat.embeddings.base-url == the ollama URL.
  - >-
    The remote-branch PRIVACY DISCLOSURE text in 4-llm.sh is updated: the
    embeddings line changes from "sent for vectorization" (remote) to stating
    embeddings run LOCALLY and never leave the machine. grep -n 'embeddings'
    prod/scripts/4-llm.sh in the disclosure block reflects local-only.
  - >-
    A new RemoteLlmWiringTest (infochat-llm-adapter, ProcessBuilder-driving the
    real 4-llm.sh with a fake docker on PATH, mirroring LlamacppWiringTest)
    asserts: after a remote-backend run, infochat.embeddings.base-url is the
    ollama URL and infochat.embeddings.model is nomic-embed-text, while the
    generative tasks carry the remote base-url and the ${INFOCHAT_LLM_API_KEY}
    api-key reference.
  - >-
    SETUP_GUIDE.md §"Step 4 — Which AI model?" (remote row) is updated to state
    that even with a remote chat backend, embeddings run locally (a small Ollama
    is started for the nomic embedder), so a remote provider need NOT serve an
    embeddings model.
  - >-
    `mvn -B clean verify` from the repo root exits 0; the existing
    LlamacppWiringTest / SwitchLlmWiringTest / DoctorWiringTest /
    SimpleXProvisioningWiringTest / AdapterAdminPromptWiringTest still pass.
test_plan:
  adds:
    - "infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java — drives 4-llm.sh remote branch under a fake docker; asserts embeddings wired to local ollama, generative tasks wired remote."
  preserves:
    - all wizard wiring tests currently green on main
spec_refs:
  - "docs/spec/llm.md §Embedding pipeline"
decision_refs: []
reviews: []
escalations: []
revisions:
  - date: 2026-06-30
    reason: "clarity-fail rework (run bounded self-refine, prose-only) — clarity FAIL: SPEC-REFS-VALID blocker 'docs/spec/decisions.md D27' is ANCHOR-NOT-FOUND (D27 is a decisions-log table row, not an ATX heading, so the spec_refs anchor-resolution algorithm cannot resolve it). Verified D27 is the 'Hardware profiles' row (decisions.md:44), not the frozen-embedding invariant, and clarity's SELF-CONTAINED-CHECK passed (spec_refs are supplementary, not load-bearing), so dropping it loses no implementation-required context. Also tightened the imprecise 'docs/spec/llm.md §Embeddings' ref (resolved to the H1 title) to '§Embedding pipeline', the real section at llm.md:177 (clarity WARNING). No acceptance, out_of_scope, files_scope, files_budget, complexity, or intent change — spec_refs only."
    prior_values: |
      spec_refs (pre-refine):
        - "docs/spec/llm.md §Embeddings"
        - "docs/spec/decisions.md D27"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: ""
  verdict: ""
  warnings: []
  blockers: []
---

# M1-529: Remote backend must host embeddings locally (it currently mis-routes them)

## Context

Verified during the setup-wizard review (flaws.md F11). The `remote` branch of
`prod/scripts/4-llm.sh` calls `set_all_base_urls "$base_url"`, which sets
`infochat.embeddings.base-url` to the operator's remote endpoint, and sets
`infochat.embeddings.api-key` — but it **never overrides
`infochat.embeddings.model`**, so it stays the baked default `nomic-embed-text`
at `dimension=768` (`infochat-collector/.../application.properties:508-514`).

Embeddings are a hard, frozen invariant: `allow-model-change=false`, the pgvector
column is dimension-fixed, and `EmbeddingMetadataStartupGuard` refuses Collector
startup on any `(model, dimension)` mismatch. Commercial OpenAI-compatible
providers almost never serve a model literally named `nomic-embed-text` at
768-dim — they serve their own (e.g. OpenAI `text-embedding-3` at 1536). So a
`remote` setup today either (a) fails the embeddings call (no such model → posts
never vectorize) or (b) returns a different dimension → the startup guard blocks
the Collector. Remote **chat** works; remote **embeddings** is broken by default.

Verified facts: `ollama` serves chat+embeddings from one instance; `llamacpp`
needs a second `llamacpp-embeddings` instance OR a co-running ollama for the
nomic embedder; the `remote` branch starts NO local embedder.

## Acceptance

See the YAML `acceptance:` list. In prose: record a decision that embeddings
always run on a local nomic-768 backend; make the `remote` branch co-run a small
Ollama for the nomic embedder (reusing the existing llamacpp-branch
ollama-embeddings pattern) and wire `infochat.embeddings.*` at that local URL
while keeping the six generative tasks remote; update the privacy disclosure
(embeddings now local — a privacy improvement) and the guide; add a
`RemoteLlmWiringTest`.

## Out-of-scope

See the YAML `out_of_scope:` list. The load-bearing exclusion: do NOT touch the
frozen embedding model/dimension contract — this ticket changes only the HOST of
the fixed nomic embedder for the remote-chat case.

## Notes

- **Decision direction.** The recommended path (force local embeddings) is the
  safest given the frozen-embedding invariant and is a privacy win. The
  considered alternative — keep remote embeddings but make the model + dimension
  operator-configurable with an explicit 768-dim confirm — is rejected as the
  default because it is fragile (cross-provider vector incompatibility, the
  startup guard, no stable nomic-768 across providers). If the spec owner prefers
  the configurable-remote path, refine this ticket before implementation; the
  privacy disclosure already lists embeddings as a remote exposure, so that
  decision must be recorded either way.
- **Reuse, don't reinvent:** `4-llm.sh` already has the ollama-embeddings
  provisioning block inside the `llamacpp` branch (start ollama, poll readiness,
  `ollama pull "$NOMIC_OLLAMA_MODEL"`, point embeddings at `$OLLAMA_URL`). Factor
  or mirror it for the `remote` branch; a few duplicated lines beats a premature
  shared helper unless one already fits.
- **F10 interaction:** with embeddings now always local, the `remote-llm` profile
  + `remote` backend remains the only remote path; the local-backend guards (F10,
  M1 wizard) are unaffected.
