---
id: M1-213
title: "TranslationProvider module placement: move to the LLM adapter or amend the spec"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/LlmTranslationProvider.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/TranslationPipeline.java
  - docs/spec/llm.md
  - docs/design/09-reference.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingSpisLoadTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/spi/AllSpisLoadIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - TranslationProvider's method surface and semantics — only the file's module/package home is in question
  - LlmTranslationProvider's implementation and the translation pipeline behavior — import lines only
  - the module DAG itself — verified at draft time that either direction leaves it unchanged (messaging-adapter main has zero internal consumers of the interface; provider already depends on both modules; the sibling-modules-don't-depend-on-each-other rule is untouched)
  - 09-reference's other module-table rows — M1-210's doc-truth sweep owns them; coordinate textually on the shared file
acceptance:
  - "A decision is recorded and applied: EITHER (a) MOVE — TranslationProvider relocates to infochat-llm-adapter under app.zcat.infochat.llm, matching docs/spec/llm.md §SPI shape (\"The LLM adapter exposes pluggable interfaces (decision D32): … **`TranslationProvider`** — text + (from, to) → text.\") and 09-reference's llm-adapter row (which already names it there); all importing sites updated (draft-time sweep: LlmTranslationProvider, TranslationPipeline, MessagingSpisLoadTest [loses its pin], AllSpisLoadIT, TranslationPipelineTest, LlmTranslationProviderTest, DigestRendererTest, SummaryCommandHandlerTest, RetryCommandHandlerTest) and ModelTask's placement javadoc updated; OR (b) AMEND — docs/spec/llm.md §SPI shape records the messaging-adapter placement with the presentation-layer rationale ModelTask's javadoc already documents (\"the higher-level TranslationProvider SPI (presentation-layer concern, lives in infochat-messaging-adapter) is a different surface\"), and 09-reference's llm-adapter row stops claiming the file"
  - "Whichever direction: spec, design 09-reference, ModelTask's javadoc, and the file's actual location all agree after this ticket"
  - "mvn -B clean verify from the repo root exits 0 (under (a), green build proves the package move broke no consumer)"
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/MessagingSpisLoadTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/spi/AllSpisLoadIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
decision_refs:
  - D29
  - D32
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-213: TranslationProvider module placement — move or amend

## Context

Unified finding A8 (`deep-code-review/v2/UNIFIED.md` §2):
TranslationProvider.java lives in infochat-messaging-adapter while
docs/spec/llm.md §SPI shape lists it among the LLM adapter's SPIs and
design 09-reference's module table says the llm-adapter module carries
"`LlmProvider`, `EmbeddingProvider`, `TranslationProvider` SPIs and
impls". Re-grounded 2026-06-07: both documents still place it in the
LLM adapter; the file is still in messaging-adapter; ModelTask's
javadoc documents the messaging-adapter placement as a deliberate
"presentation-layer concern" — so this is a genuine two-sided D-level
decision (user call at start), not a one-way drift.

Draft-time verification of the move direction's safety: the interface
has zero consumers inside messaging-adapter main sources (only the
SPI load test pins it); the sole implementation and all callers are in
the provider, which depends on both modules — so relocation changes no
module-DAG edge.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T31 leg (c) under `deep-code-review/v2/`
  (kimi-folder arch F6, mimo arch F3).
- 09-reference.md is also in M1-210's files_scope (different rows) —
  coordinate or serialize to avoid textual conflicts.
- Under (a) the import sweep spans nine files (counted by grep at
  draft time); the budget carries them plus the two doc files.
