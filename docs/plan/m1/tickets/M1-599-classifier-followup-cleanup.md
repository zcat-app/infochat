---
id: M1-599
title: "Tagger title-wrap (D21) + classifier switch-llm/4-llm tooling"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 12
files_scope:
  - infochat-llm-adapter/src/main/resources/prompts/tagger.md
  - infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - docs/design/05-llm-and-embeddings.md
  - prod/switch-llm.sh
  - prod/scripts/4-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/LlamacppWiringTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/SwitchLlmWiringTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The classifier ingest stage itself (ClassifierWorker, V57 migration,
    post.classification, ReadyPromoter gate, ModelTask.CLASSIFIER, LlmRouter,
    prompts/classifier.md). That is M1-597 (done). This ticket does NOT touch the
    classifier's Java, migration, or its own prompt — classifier.md already wraps
    its title inside the delimiter (M1-597 redteam remediation).
  - >-
    The EntityExtractorWorker inline prompt. Verified 2026-07-08: it ALREADY
    wraps {{title}} AND {{body}} inside the {{id}}...{{id}} delimiters (no D21
    gap), so no code change. Only the docs/design/05 §5.4.3 entity-extractor
    example (which misleadingly shows `Title: {{title}}` outside the wrapper) is
    corrected for doc accuracy — a doc-only edit inside the already-scoped
    05-llm-and-embeddings.md.
  - >-
    Changing the SHIPPED default routing for the classifier. Under every profile
    the classifier stays on its local-Ollama default (M1-597 added no %remote-llm
    override); this ticket only teaches the switch-llm/4-llm tooling that the
    classifier EXISTS so an operator-initiated remote switch routes AND discloses
    it (like tagger/entity), not that the default changes.
  - >-
    Re-wrapping the security-judge (Stage 2) or summarizer/chat/translator
    prompts. Stage 2 does not weave the title the same way, and the
    summarizer/chat/translator are query-time provider prompts out of the ingest
    prompt-injection surface this ticket addresses.
acceptance:
  - >-
    D21 consistency (redteam follow-up from M1-597): prompts/tagger.md and
    prompts/tagger-fallback.md move `Post title: {{title}}` from ABOVE the
    <<<UNTRUSTED_CONTENT id="{{id}}">>> opener to INSIDE the delimited block
    (mirroring the M1-597 classifier fix), so the upstream-untrusted title is
    delimiter-wrapped like the body per §Prompt-injection defenses. TaggerWorker
    .renderPrompt is unchanged (it substitutes tokens wherever the template
    places them).
  - >-
    NAMED TEST. TaggerWorkerTest.renderPrompt_wrapsTitleInsideDelimiter (new)
    renders the PRIMARY tagger prompt and asserts the substituted title sits
    between the per-call {{id}} delimiter markers (use lastIndexOf for the opener
    /closer — the prompt PREAMBLE also names the delimiter tokens when it explains
    the wrapper, exactly as ClassifierWorkerTest does). Red-before / green-after
    the tagger.md change.
  - >-
    docs/design/05-llm-and-embeddings.md §5.4.2 Tagger example is synced to the
    new title-inside-delimiter layout; §5.4.3 Entity extractor example is
    corrected to show the title inside the delimiter (matching the actual
    EntityExtractorWorker inline prompt, which already wraps it — the current doc
    is stale).
  - >-
    prod/switch-llm.sh gains `classifier` in LLM_TASKS (line ~43:
    "security tagger entity summarizer chat translator" → adds classifier), and
    prod/scripts/4-llm.sh wires the classifier task the same way it wires the
    other per-task remote endpoints, so an operator remote/llamacpp switch routes
    the classifier too (rather than silently leaving it on localhost, which fails
    every classifier call in a remote-only host).
  - >-
    The Phase-4 privacy disclosure block in prod/switch-llm.sh names the
    classifier among the tasks whose post bodies now leave the host on a remote
    switch (the disclosure commitment in docs/spec/llm.md §Privacy /
    §Secrets handling — "naming exactly which generative tasks now call a remote
    provider"), so the disclosure stays complete for the 7-task set.
  - >-
    The wiring tests RemoteLlmWiringTest, LlamacppWiringTest, and SwitchLlmWiringTest
    are updated so their expected/asserted task set includes `classifier` (they
    currently pin the six-task list). Red-before / green-after the script changes.
  - mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      TaggerWorkerTest.renderPrompt_wrapsTitleInsideDelimiter — asserts the tagger
      primary prompt wraps the title inside the {{id}} delimiter (D21).
  modifies:
    - >-
      RemoteLlmWiringTest / LlamacppWiringTest / SwitchLlmWiringTest — expected
      task set grows from six to seven (adds classifier), matching the updated
      switch-llm.sh / 4-llm.sh scripts.
  preserves:
    - all tests currently green on main
    - >-
      classifier behavior (M1-597) and entity/security/summarizer prompt handling
      — untouched.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/llm.md §Privacy notes for remote providers
  - docs/design/05-llm-and-embeddings.md §5.4.2 Tagger
  - docs/design/05-llm-and-embeddings.md §5.4.3 Entity extractor
decision_refs:
  - D21
redteam_findings: []
redteam_audits: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
---

# M1-599: tagger title-wrap (D21) + classifier switch-llm/4-llm tooling

## Provenance

Two follow-ups deferred out of M1-597 (real per-post classification ingest
stage, merged 2026-07-08 @ `4970699f`):

1. **D21 consistency (redteam finding, out-of-scope for M1-597).** The M1-597
   redteam flagged that the classifier prompt rendered the untrusted post
   *title* OUTSIDE the per-call `{{id}}` delimiter; it was remediated in-branch
   for the classifier. Verified 2026-07-08 that the pre-existing **tagger**
   primary (`tagger.md`) and fallback (`tagger-fallback.md`) prompts have the
   SAME gap (`Post title: {{title}}` above the `<<<UNTRUSTED_CONTENT>>>` opener).
   The **entity** worker does NOT — its inline prompt already wraps title + body
   inside the delimiters (only the §5.4.3 doc example is stale). Impact is
   bounded to nil today (the tagger's controlled-vocabulary filter + bootstrap
   fallback constrain any injected output), so this is a LOW defense-in-depth
   consistency fix, same class as the M1-597 remediation.

2. **Operator tooling (redteam out-of-model note + plan-writer Risk 2).**
   `prod/switch-llm.sh` (`LLM_TASKS`) and `prod/scripts/4-llm.sh` wire exactly
   the six generative tasks and never learned about the new `classifier`
   ModelTask. Consequence today is SAFE (the classifier stays on its
   local-Ollama default under every shipped profile — no post body leaves the
   host), but a remote/llamacpp switch leaves the classifier pointed at
   `localhost`, failing every classifier call in a remote-only host, and the
   Phase-4 privacy disclosure never names it. Teaching the tooling routes the
   classifier on a remote switch AND names it in the disclosure.

## Why one ticket

Both are M1-597 completeness follow-ups on the classifier-integration surface,
both security/privacy-relevant (prompt-injection wrapping + the remote-routing
disclosure), and file-disjoint from M1-597's merged code. Kept whole; if review
finds the prompt half and the shell-tooling half unwieldy, raise it via
escalate→decompose rather than silently splitting.

## Notes

- The tagger fix mirrors the M1-597 classifier remediation exactly (move the
  title inside the wrapper; the worker's renderPrompt is unchanged). The
  `TaggerWorkerTest` render assertion must use `lastIndexOf` for the delimiter
  opener/closer because the prompt preamble also names the delimiter tokens when
  it explains the wrapper — the same subtlety `ClassifierWorkerTest` handles.
- `security_relevant: true` → redteam gate (the tagger prompt-injection surface
  + the remote-routing privacy disclosure).
