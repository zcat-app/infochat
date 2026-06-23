---
id: M1-434
title: "cleanup: deep-review low-severity sweep — cache-token metric, embedding providerName, connected() gauge, SQL seam"
status: pending
created: 2026-06-23
last_updated: 2026-06-23
blocked_by: []
files_budget: 7
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredEmbeddingProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "A distinct cached-token counter (a separate llm.tokens.cached metric) is NOT added — that widens the metrics catalogue (design §5.9). This ticket only corrects the existing llm.tokens.in to stop undercounting; the cached/uncached split is a larger change left out."
  - "OpenAiCompatibleProvider's usage parser is untouched — it never reports Anthropic cache fields, and asLong(0L) defaults keep its shape behavior identical."
  - "SimpleXWebSocketClient.isClosed() is consumed, not changed; the Signal sibling (SignalAdapter.connected() / SignalJsonRpcClient.isConnected()) carries the symmetric limitation and is left as-is — its peer-closed window is a separate, lower-value gauge concern, not part of this sweep."
  - "ReEvaluationJob.enumerateCandidates is a whitespace/seam correction only — the rendered SQL semantics are unchanged (it parses today because the next concatenated token begins with whitespace)."
  - "No new metric labels, no SPI signature changes, no Flyway migration."
acceptance:
  - "AnthropicProvider folds cached input tokens into the reported input count: the TokenUsage input value becomes usage.input_tokens + usage.cache_read_input_tokens(default 0) + usage.cache_creation_input_tokens(default 0), using asLong(0L) so a response with no cache activity is byte-identical to today (AnthropicProvider.java:228-233). The output-token value is unchanged."
  - "A test under infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl asserts that an Anthropic usage object carrying cache_read_input_tokens / cache_creation_input_tokens yields a TokenUsage input that includes them, and that a usage object without those fields yields the same value as today."
  - "MeteredEmbeddingProvider overrides providerName() to forward delegate.providerName(), mirroring MeteredLlmProvider.providerName() (MeteredLlmProvider.java:93-94), with a comment stating the same reason (the interface default walks getClass() on the CDI decorator subclass and would not reach the real provider's stable constant)."
  - "A test under infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics asserts MeteredEmbeddingProvider.providerName() returns the delegate's stable name, not a decorator/proxy-derived name."
  - "SimpleXAdapter.connected() additionally consults the client's closed state: it returns false when the WebSocket has been peer-closed, via a local copy of the volatile field and !ws.isClosed() (SimpleXWebSocketClient.isClosed() at line 362). Send/classification behavior is unchanged."
  - "ReEvaluationJob.enumerateCandidates carries an explicit space delimiter at the `?::INTERVAL` / `AND` concatenation seam (ReEvaluationJob.java:553-562); the rendered SQL is semantically unchanged and ReEvaluationJob's existing tests stay green."
  - "All tests currently green on main remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl (Anthropic cache-token accounting test)
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics (MeteredEmbeddingProvider.providerName forwarding test)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/messaging.md §Capability flags (minimum set)
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-434: deep-review low-severity sweep

## Context

The 2026-06-23 `/deep-code-review full` run
(`.reviews/deep-review/full-2026-06-23-0957/`) surfaced six findings — one
medium (filed as **M1-432**), one low SECURITY (filed as **M1-433**), and four
remaining **low** items with no live correctness or security impact. This ticket
batches those four into one sweep so each is fixed once at its source, matching
the precedent set by **M1-412** (the previous deep-review-low cleanup).

The four items are independent; they are bundled per the explicit request to
avoid a spray of tiny tickets, not because the changes are coupled.

## Acceptance

See frontmatter. Two behavior changes worth a test (Anthropic cache-token
accounting; the embedding-decorator `providerName()` override) plus two narrow
corrections (the `connected()` gauge consulting the peer-closed state; a
one-character SQL-seam whitespace fix). The two new tests mirror existing
siblings (the LLM-side metered decorator and the Anthropic usage-parse tests).

## Out-of-scope

See frontmatter. No new metric labels or counters, no SPI signature changes, no
migration. The Signal-side symmetric `connected()` limitation and the
OpenAI-compatible usage parser are deliberately left untouched.

## Notes

- **Per-finding source map** (all verified 2026-06-23):
  - Anthropic cache tokens: `AnthropicProvider.java:228-233` reads only
    `input_tokens`, while the provider deliberately enables
    `cache_control: ephemeral` (so cache hits report under
    `cache_read_input_tokens`), making `llm.tokens.in` undercount. →
    `04-module-infochat-llm-adapter.md#F2` (PERFORMANCE, low)
  - Embedding `providerName()`: `MeteredEmbeddingProvider.java:37` has no
    `providerName()` override, while its documented LLM sibling
    `MeteredLlmProvider.java:93-94` does (with a comment explaining why it is
    mandatory). →  `04-module-infochat-llm-adapter.md#F3` (MAINT-DRIFT, low)
  - `connected()` gauge: `SimpleXAdapter.java:546` checks `webSocket != null`
    but not the client's own `isClosed()` (`SimpleXWebSocketClient.java:362`),
    so a peer-closed socket reads as connected until the supervisor reconnects.
    → `05-module-infochat-messaging-adapter.md#F1` (MAINT-DRIFT, low)
  - SQL seam: `ReEvaluationJob.java:553-562` `enumerateCandidates` relies on an
    implicit trailing-space token boundary at the `?::INTERVAL` / `AND` seam. →
    `06-module-infochat-collector.md#F2` (MAINT-DRIFT, low)
- **security_relevant: false** — none of the four touch a documented security
  property (the cache-token item is observability accuracy; the others are
  gauge/maintainability). The SECURITY and privacy items from the same run are
  carried by M1-433 and M1-432 respectively.
- **Implementer note:** the four items are independent and may be split
  per-module without loss if the reviewer prefers; they are bundled only to
  avoid four tiny tickets.
- Full reports: `.reviews/deep-review/full-2026-06-23-0957/` (`00-summary.md`
  first).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-434-cleanup-deep-review-low-sweep.md
```
