---
id: M1-559
title: ChatAgent intercepts the structured refusal marker before delivery (F-live-9)
status: done
created: 2026-07-04
last_updated: 2026-07-04
clarity_check:
  date: 2026-07-04
  verdict: PASS
  warnings: []
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 240
      removed: 11
redteam_findings: []
redteam_audits:
  - date: 2026-07-04
    verdict: CLEAN
    base: b324759d2167bf08f51e8c97573d62c64522d706
    head: m1/M1-559-chatagent-intercepts-the-struc (working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-559-2026-07-04.md
    out_of_model_count: 1
    note: |
      Pre-commit audit of the working-tree diff (branch had no commits yet;
      base is the fork point). CLEAN — the intercept closes the F-live-9
      marker leak as promised. One advisory out-of-model item: a post-cap
      final response mixing a refusal marker with a trailing tool-call
      fragment evades the anchored predicate (stripToolCalls then leaves the
      bare marker); candidate low-priority follow-up ticket, not a
      threat-model commitment.
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - SummaryProseGenerator — its interception (line ~119) is correct and
    stays byte-identical; this ticket brings the chat path up to the same
    contract
  - changing the refusal-marker protocol itself (ChatPromptBuilder's
    CHAT_SYSTEM_PROMPT instruction text, the marker literal, the summary
    prompt) — the marker convention is spec-anchored
    (security.md §Prompt-injection defenses)
  - tuning WHEN models refuse (the spurious-refusal model-quality question
    belongs to the F-live-8 / default-model discussion, not the intercept)
  - the tool-loop iteration cap, tool dispatch, or any other ChatAgent
    behavior — only the terminal-text refusal check is added
acceptance:
  - "In the chat turn path, the terminal text returned by the tool loop is
    checked with the same predicate SummaryProseGenerator uses
    (trimmed text startsWith \"[REFUSAL:\" && endsWith \"]\") BEFORE
    persistence and delivery. On match: the user receives a new
    deterministic bundle string (key error.chat.refused, e.g. \"I can't
    help with that request.\"), the raw marker (or any part of it) is never
    delivered, and the turn is treated as a degraded turn exactly like the
    ERROR_CHAT_UNAVAILABLE path — no user/assistant chat_message rows are
    persisted for it."
  - "The intercept logs at WARN with the userId only — the refusal reason
    is LLM-authored text derived from untrusted content and MUST NOT be
    logged (the same content-free-logging discipline as D37 and the
    SummaryProseGenerator intercept, which logs only the topicId)."
  - error.chat.refused is added to BOTH en.properties and cs.properties
    (D43 bilateral keyset — BundleLoaderTest fails on a missing twin) and
    as a BundleKeys constant beside the other error.chat.* keys.
  - "A new test drives a chat turn whose (fake) LLM returns
    '[REFUSAL: because-reasons]' and asserts: the delivered reply is the
    bundle string, the marker literal appears nowhere in the delivered
    text, and no chat_message rows were persisted for the turn. A second
    case asserts a reply that merely CONTAINS the substring mid-prose
    (does not start with it) is delivered unchanged — the predicate is
    anchored, not a substring scan."
  - mvn verify is green.
test_plan:
  adds:
    - ChatAgentRefusalInterceptTest — marker intercepted (bundle reply, no
      persistence, no leak) + anchored-predicate negative case
  preserves:
    - the full pre-existing suite, in particular the existing ChatAgent
      tests and SummaryProseRefusalDegradeTest (whose "the marker MUST NOT
      appear in user-visible output" contract this ticket extends to chat)
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs:
  - D21
  - D37
  - D43
---

## Context

Found live 2026-07-04 (F-live-9, live-e2e HANDOFF), during the ollama
backend verification: a benign DM ("Tell me about the recent security
advisory from my sources") made `llama3.2:3b` emit the D21 structured
refusal, and the bot delivered the raw protocol string —
`[REFUSAL: unable to assist with untrusted source information]` — to the
user verbatim (chat_message seq 3 + the SimpleX client DB both show it).

`ChatPromptBuilder:44` instructs the model to emit `[REFUSAL: <reason>]`
for action requests inside untrusted-content wrappers, but nothing in the
chat path handles the marker. The summary path already does this right:
`SummaryProseGenerator:119` intercepts the same marker and degrades, with
a comment citing security.md §Prompt-injection defenses — "never surface
the marker (or any LLM-authored prose) to the user". Chat instructs the
protocol without implementing its receiving end.

Why it matters beyond polish: the marker is protocol surface. Delivering
it verbatim (a) leaks the injection-defense convention to exactly the
counterparty the defense is aimed at, letting a group attacker probe which
phrasings trip it, and (b) hands the LLM a way to author what looks like
bot-authoritative bracketed status text. A deterministic bundle string
closes both.

## Fix shape

One anchored check on the tool loop's terminal text, mirroring the summary
path's predicate and logging discipline; a new bundle key (en+cs); reuse
of the existing degraded-turn handling (deliver friendly string, persist
nothing). The mid-loop case needs no extra handling: a refusal text
contains no tool-call pattern, so it always surfaces as the terminal text.
