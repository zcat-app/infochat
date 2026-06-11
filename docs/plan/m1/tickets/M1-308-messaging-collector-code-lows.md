---
id: M1-308
title: "Messaging/collector code lows: dead surfaces, helper drift, small perf/correctness"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 20
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2Worker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionPruner.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/testing/StubLlmProvider.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/QuarkusBootstrapTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Behaviour changes beyond the enumerated items.
  - SimpleX classification/race/cap items (M1-294) and the edit fallback (M1-285).
  - EmbeddingResult construction clone and ReadyPromoter/PriceSnapshotStore writable seams — report records leave-as-is as defensible; backlogged, do not touch.
acceptance:
  - "U-54: InMemoryAdapter's write-only handles map and InMemoryMessageHandle record (never read; javadoc promises state it doesn't deliver) are removed or made real — default remove; the in-memory adapter's tests stay green."
  - "U-50: the log-capture test helper is consolidated to one per module (messaging-adapter: the signal CapturingLogHandler plus the drifted formatted()-semantics copies in simplex tests, e.g. SimpleXSubprocessTest/SimpleXWebSocketClientTest; llm-adapter: the copies in LlmRouterUnknownDefaultTest/LlmRouterStartupGuardLocalOnlyTest); the parameter-aware variant wins (it catches formatted-message secrets the message-only variant misses); top-level package-private helpers per the recorded no-inner-class-fakes rule."
  - "U-68 messaging items, each verified 2026-06-11: (a) the dead typing surface — SimpleXWebSocketClient.sendFireAndForget (:311) and SimpleXMessageCodec.encodeTypingCommand (:164), zero production callers, javadocs contradicting each other about delivery guarantees — is deleted (MEDIUM per fable-5 because the comments actively mislead). (b) SimpleXMessageCodec.jsonString (:225) identity function is inlined away. (c) firstTextual's fixed-arity unrolled overloads collapse to varargs (call sites :578/:593). (d) SimpleXAdapter.requireKnownAndOpen's redundant double-synchronization is reduced to the outer lock (call sites :440/:454). (e) the encode-to-measure getBytes(UTF_8).length sites in the two codecs reuse a single measured byte array where the bytes are subsequently used (sweep both codecs by grep; opus-47 counted 5 sites)."
  - "U-68 collector items: (f) Stage2Worker releases the LLM permit before its verdict DB writes (today held across them, shrinking eval concurrency during DB latency). (g) PartitionPruner reuses one connection across its drop loop instead of connection-per-drop (cold maintenance path; LOW). (h) StubLlmProvider's mutable state becomes synchronized/volatile per its multi-thread use (flake insurance). (i) the empty QuarkusBootstrapTest body in collector gains the §8 plain-text assertion or a comment stating what booting alone proves."
  - "U-72 rider: the messaging-adapter's two logging facades converge on one (the signal package mixes java.lang.System.Logger with the jboss facade used elsewhere; fable-5/05#F9, gpt-55#L-08); mechanical swap, no message changes."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-308: Messaging/collector code lows: dead surfaces, helper drift, small perf/correctness

## Context

Deep-review v5 verified **U-50**, **U-54**, the messaging/collector members
of **U-68**, and the logging-facade item from U-72
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; sources `fable-5/05#F2/#F6/#F7/#F9`,
`opus-47/05#F4/#F5`, `opus-48/05#F3`, `deepseek/05#F3`, `mimo/4#F2`,
`mimo/5#F3`, `opus-47/06#F1/#F5`, `fable-5/06#F11`, `opus-47/06#F4`,
`gpt-55#L-08` — gitignored; all items inlined with file:line in acceptance,
anchors re-verified 2026-06-11).

M1-260/M1-261 are the v4 precedent for this sweep shape.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — note the two explicit leave-as-is items.

## Notes

- (a) deletes production code with zero callers — grep call sites
  including tests first (recorded rule); the contradicting javadocs are
  why this is MEDIUM, not cosmetic.
- (f) keep the ordering: verdict computation under permit, writes after
  release — do not move the verdict itself out.
- PartitionPruner was MEDIUM in opus-47's report; the unified report
  re-rated it LOW (cold path) — don't inflate it back during review.
- Coordination: M1-294 and M1-285 edit the same simplex files; this sweep
  lands last among the messaging cluster.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-308-*.md
```
