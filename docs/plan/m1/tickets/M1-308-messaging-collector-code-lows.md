---
id: M1-308
title: "Messaging/collector code lows: dead surfaces, helper drift, small perf/correctness"
status: done
created: 2026-06-11
last_updated: 2026-06-13
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
  - Logging-facade convergence (former U-72). Dropped 2026-06-13 (premise-fail refine) — the slf4j/jboss mix spans both modules and is a codebase-wide canonical-facade decision, not a mechanical messaging-adapter rider; see the dropped U-72 acceptance note and the escalation entry.
acceptance:
  - "U-54: InMemoryAdapter's write-only handles map and InMemoryMessageHandle record (never read; javadoc promises state it doesn't deliver) are removed or made real — default remove; the in-memory adapter's tests stay green."
  - "U-50: the log-capture test helper is consolidated to one per module (messaging-adapter: the signal CapturingLogHandler plus the drifted formatted()-semantics copies in simplex tests, e.g. SimpleXSubprocessTest/SimpleXWebSocketClientTest; llm-adapter: the copies in LlmRouterUnknownDefaultTest/LlmRouterStartupGuardLocalOnlyTest); the parameter-aware variant wins (it catches formatted-message secrets the message-only variant misses); top-level package-private helpers per the recorded no-inner-class-fakes rule."
  - "U-68 messaging items, each verified 2026-06-11: (a) the dead typing surface — SimpleXWebSocketClient.sendFireAndForget (:311) and SimpleXMessageCodec.encodeTypingCommand (:164), zero production callers, javadocs contradicting each other about delivery guarantees — is deleted (MEDIUM per fable-5 because the comments actively mislead). (b) SimpleXMessageCodec.jsonString (:225) identity function is inlined away. (c) firstTextual's fixed-arity unrolled overloads collapse to varargs (call sites :578/:593). (d) SimpleXAdapter.requireKnownAndOpen's redundant double-synchronization is reduced to the outer lock (call sites :440/:454). (e) RESOLVED-NO-OP (premise-fail refine 2026-06-13): swept all five getBytes(UTF_8).length sites in the two codecs and the chunker (SimpleXMessageCodec, SignalMessageCodec, SimpleXOutboundChunker) — each measures length and discards the array; none subsequently reuses the measured bytes, so there is no array to collapse. No diff for this sub-item; leave as-is."
  - "U-68 collector items: (f) Stage2Worker releases the LLM permit before its verdict DB writes (today held across them, shrinking eval concurrency during DB latency). (g) PartitionPruner reuses one connection across its drop loop instead of connection-per-drop (cold maintenance path; LOW). (h) StubLlmProvider's mutable state becomes synchronized/volatile per its multi-thread use (flake insurance). (i) the empty QuarkusBootstrapTest body in collector gains the §8 plain-text assertion or a comment stating what booting alone proves."
  - "U-72 rider: DROPPED (premise-fail refine 2026-06-13). The premise is false — there is no java.lang.System.Logger anywhere in messaging-adapter (git log -S confirms it never existed in the signal package). The real state is a two-facade mix (simplex=org.slf4j across 3 files: SimpleXAdapter/SimpleXWebSocketClient/SimpleXSubprocess; signal=org.jboss.logging across 4 files), and the collector module is likewise mixed (Stage2Worker=slf4j vs PartitionPruner=jboss). Converging is a codebase-wide canonical-facade decision and a ~32-call-site rewrite with printf-vs-{} placeholder footguns — not a mechanical no-message rider — so it is out of scope for this code-lows sweep. No diff for this item."
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
revisions:
  - date: 2026-06-13
    reason: premise-fail rework
    escalation_reason: premise-fail
    snapshot:
      acceptance_items_rewritten:
        - "(within U-68 messaging item) (e) the encode-to-measure getBytes(UTF_8).length sites in the two codecs reuse a single measured byte array where the bytes are subsequently used (sweep both codecs by grep; opus-47 counted 5 sites)."
        - "U-72 rider: the messaging-adapter's two logging facades converge on one (the signal package mixes java.lang.System.Logger with the jboss facade used elsewhere; fable-5/05#F9, gpt-55#L-08); mechanical swap, no message changes."
reviews:
  - round: 1
    date: 2026-06-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 18
      added: 269
      removed: 382
escalations:
  - date: 2026-06-13
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — developer-detected during start-time grounding, before any code change.
      Two acceptance items name code states that do not exist on main (dee3597c):
      (1) U-68 item (e) "reuse a single measured byte array where the bytes are
          subsequently used": all five getBytes(UTF_8).length sites in the two
          codecs + chunker (SimpleXMessageCodec :246/:381/:492, SignalMessageCodec
          :297, SimpleXOutboundChunker :108) measure length and discard the array;
          no site reuses the measured bytes, so the qualifier matches zero sites.
      (2) U-72 "the signal package mixes java.lang.System.Logger with the jboss
          facade": no System.Logger exists anywhere in messaging-adapter main
          (git log -S confirms it never existed in the signal package); the signal
          package is uniformly org.jboss.logging. The actual two-facade mix is
          simplex=org.slf4j (3 files) vs signal=org.jboss.logging (4 files).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-13
  verdict: WARN
  warnings:
    - 'ACCEPTANCE-RUNNABLE item 1 (U-54): "removed or made real" has an undefined alternative branch; simplest fix is to keep only "default remove".'
    - 'ACCEPTANCE-RUNNABLE item 4(i) (QuarkusBootstrapTest): "§8 plain-text assertion" undefined inline; clarify whether existing comment satisfies the item or specify the assertion.'
    - 'OUT-OF-SCOPE-SPECIFIC: "Behaviour changes beyond the enumerated items" is circular and adds no boundary information.'
  blockers: []
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
