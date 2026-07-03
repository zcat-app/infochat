---
id: M1-553
title: Wizard wiring tests answer M1-550's step-4 LLM timing prompts
status: pending
created: 2026-07-03
last_updated: 2026-07-03
blocked_by: []
files_budget: 2
files_scope:
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - prod/scripts/4-llm.sh changes — the prompts are correct, F-live-5-approved
    behavior (M1-550); only the test fixtures lag behind them
  - the inert-diff process-gate hole that let M1-550 merge without a verify
    run (prod/scripts/*.sh ARE exercised by these wiring tests) — that is a
    process-doc amendment, not a code change
  - other wizard-step tests (1-profile / 2-secrets / 3-postgres) — their
    scripts gained no new prompts
  - resuming M1-551 — it reopens separately once this ticket is done
acceptance:
  - Every runWizard stdin fixture in LlamacppWiringTest and
    RemoteLlmWiringTest supplies answers for the four M1-550 prompt_timing
    reads (infochat.llm.chat.timeout-ms, infochat.llm.chat.max-tokens,
    infochat.llm.summarizer.timeout-ms, infochat.llm.summarizer.max-tokens)
    appended after the existing answers — blank lines where the test means
    "accept the recommended default".
  - At least one llamacpp-backend test asserts the four keys land in the
    written properties with the local-backend vps-profile recommendations
    (chat 240000 / 600, summarizer 240000 / 400), and the remote-backend
    test asserts the backend-first remote recommendations (60000 / 1024 for
    both tasks) — pinning F-live-5's wizard output at the test level for
    the first time.
  - "The five tests red in target/m1-tick-test-M1-551-r1.log are green:
    LlamacppWiringTest.switchingAwayFromRemoteToLlamacppClearsStaleRemoteApiKeys,
    ollamaEmbeddingsShapePointsAtOllamaNomicEndpoint,
    llamacppEmbeddingsShapePointsAtEmbeddingsServiceNeverGenerativeGguf,
    fetchGgufWritesToTheVolumeComposeMounts;
    RemoteLlmWiringTest.remoteBackendWiresGenerativeRemoteButEmbeddingsLocal."
  - mvn verify is green.
test_plan:
  adds:
    - "LlamacppWiringTest: llamacpp-backend run asserts chat/summarizer
      timeout-ms and max-tokens written with the vps recommendations
      (240000/600, 240000/400)"
    - "RemoteLlmWiringTest: remote-backend run asserts all four keys written
      with the remote recommendations (60000/1024)"
  modifies:
    - "LlamacppWiringTest / RemoteLlmWiringTest stdin fixtures — extended to
      answer the four new prompts; this ticket explicitly authorizes
      modifying these pre-existing tests (they assert the wizard contract
      M1-550 changed)"
  preserves:
    - every other infochat-llm-adapter test unchanged
spec_refs:
  - docs/design/05-llm-and-embeddings.md §5.3 Provider implementations
  - docs/design/07-deployment.md §7.7.2 First-run setup wizard (`prod/setup.sh`)
decision_refs: []
---

## Context

Found by M1-551's round-1 full-suite verify (2026-07-03,
`target/m1-tick-test-M1-551-r1.log`): 5 failures in infochat-llm-adapter —
LlamacppWiringTest ×4, RemoteLlmWiringTest ×1 — all at the `runWizard`
exit-0 assertion.

**Root cause:** M1-550 added four interactive `prompt_timing` reads to
`prod/scripts/4-llm.sh` (chat + summarizer × timeout-ms + max-tokens,
F-live-5). The wiring tests drive the script with fixed scripted stdin
(e.g. `"llamacpp\n\n\n\n"`); the new reads hit EOF, `read -rp` returns
non-zero, and under `set -e` the script exits 1.

**Why main is red:** M1-550's diff was docs + shell only, so the M1
inert-diff gate classified it "zero mvn verify coverage" and merged it
without a verify run. The classification's premise is false for
`prod/scripts/*.sh` — these Java tests execute the scripts — which is a
process-gate hole tracked outside this ticket (out_of_scope).

## Fix shape

Append four answer lines to each `runWizard` stdin fixture (blank =
accept recommended default), and add value assertions for the four new
keys where the test's backend makes the recommendation deterministic:
the fixtures seed `quarkus.profile=vps`, so llamacpp-backend runs must
write chat 240000/600 and summarizer 240000/400; remote-backend runs are
backend-first and must write 60000/1024 for both tasks (recommendation
table at `prod/scripts/4-llm.sh` — the `chat_timeout_default` block).
