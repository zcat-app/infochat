---
id: M1-847
title: "Add a streaming generate shape to the LlmProvider SPI"
status: done
created: 2026-08-14
last_updated: 2026-08-15
flow: tick
reproduction: >-
  OpenAiCompatibleProviderStreamingTest#streamsChunksInOrderToTheConsumer
  (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/streaming-translation-switch.md; written at
  start 2026-08-15 and run RED first as a compile failure on the absent
  SPI, green 271-test module run after, .scratch/tick-red-M1-847.log).
analysis_ref: docs/plan/m1/tick-analysis/streaming-translation-switch.md
blocked_by: []
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/CircuitBreakingLlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredLlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY consumer of the streaming shape — ChatAgent, the notifier, and the
    eligibility gate are M1-849; this ticket adds the SPI and proves it
    against fake endpoints. (Not dead code: the sibling lands in the same
    batch.)
  - >-
    ANY user-visible behavior change and any docs/spec/** edit: the SPI
    Java surface is design-tier (llm.md §What lives in design notes), the
    display policy is M1-846's amendment, and no shipped path calls the
    new shape yet.
  - >-
    The summarizer/translator/judge tasks' prompt templates, and any change
    to the single-string `generate` contract or its callers — the new shape
    is additive; today's method and semantics are untouched.
  - >-
    RETRY/resume semantics for a failed stream (a mid-stream failure is a
    failed CALL under the existing per-task failure posture; resumable
    generation is a separate, later decision).
acceptance:
  - "OpenAiCompatibleProviderStreamingTest.streamsChunksInOrderToTheConsumer (the reproduction, written and run RED at start) passes — a fake SSE endpoint feeding `data:` frames terminated by `[DONE]` delivers the chunks to the consumer in order and yields the assembled final text plus the terminal usage frame; DeepSeekProvider inherits the impl (its subclass posture, DeepSeekProvider.java:16-18) and a DeepSeek-flavored fake stream passes the same test shape."
  - "AnthropicProviderStreamingTest passes — the Anthropic SSE event shape (its own event types) is parsed with the same consumer contract; a malformed event frame fails the call, never emits a synthetic chunk."
  - "CircuitBreakingLlmProviderStreamingTest passes — FAILURE-MODE (P12): a mid-stream connection drop classifies TRANSPORT and trips the endpoint breaker exactly like a failed single-string call (security.md §Failure handling's breaker rule), while a mid-stream application error proves reachability and does not trip it; a stream that bypasses the decorator does not exist — the wrapper chain wraps the new shape."
  - "The accumulated-body cap applies to the stream (trust boundary 9, security.md:98-132): a fake endpoint streaming past the operator-configured cap (1-8 MiB clamp posture) is cut at the cap and the partial body discarded — StreamingBodyCapTest passes; and the usage boundary checks apply to the terminal usage frame: an impossible count (negative, over the generation cap, or over the input ceiling) discards the report whole — test passes (llm.md §Bounded concurrency and observability)."
  - "MeteredLlmProviderStreamingTest passes — per-task latency and token-count metrics emit once per streaming call with the model label from operator config, never from a wire-reported string (llm.md:575-581)."
  - "Timeout and interrupt posture: the per-task timeout-ms (the M1-607 committed chat default) bounds the WHOLE streaming call and an inter-chunk stall trips the read timeout — StreamingTimeoutTest passes; and a stream read on an interrupted virtual thread aborts the call without hanging the worker (the M1-763 lesson, LlmOutputSanitizer.java:303-319) — StreamingInterruptTest passes (P11's SPI half)."
  - "LlmRouter exposes the streaming capability of the resolved provider (at minimum for CHAT_AGENT), and the startup assertion scan covers the streaming-relevant config coherence per the assertAllTasksResolve posture (LlmRouter.java:278-310) — LlmRouterStreamingCapabilityTest passes; a provider that cannot stream reports it explicitly (no silent assumption)."
  - "Every pre-existing provider/decorator/router test passes UNCHANGED — the single-string generate contract and its metrics/breaker/budget wrappers are untouched (§10; P15: the SPI tests pin the SPI contract only, nothing about the M1-849 consumer)."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderStreamingTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderStreamingTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/StreamingBodyCapTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/StreamingTimeoutTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/StreamingInterruptTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/CircuitBreakingLlmProviderStreamingTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/MeteredLlmProviderStreamingTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStreamingCapabilityTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/spec/security.md §Failure handling
decision_refs:
  - D32
  - D56
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "19 files, +1947/-44"
    verdict_file: .scratch/tick-review-M1-847-r1.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: "2026-08-15 pass — lint clean (after copying the gitignored tick-analysis/streaming-translation-switch.md into this worktree, the M1-844 pattern); citations spot-checked and hold (LlmProvider.java:36 single-string generate, :9-16 minimal-SPI javadoc, DeepSeekProvider.java:16-18 subclass, security.md:98-132 boundary 9 incl. 1-8 MiB cap + wire-label rule + usage checks, llm.md:614-630 SPI-design-tier, LlmRouter.java:278-310 assertAllTasksResolve, LlmOutputSanitizer.java:303-319 M1-763 virtual-thread interrupt lesson); both reproduction probes re-ran clean (single-string SPI; no SSE/chunked parsing in impl/); no §Census (N/A, feature slice); analysis pitfalls P11-SPI/P12/P15/P20 all present and matching; replaces: empty and no parked worktree holds a superseded SPI attempt (the 'stream' hits in the M1-817..834 worktrees are the javadoc word 'downstream'); blocked_by empty so no prior-ticket test enumeration; P20 note: the M1-819..843 batch is not fully landed (M1-836..843 pending) but none of it touches infochat-llm-adapter, the ticket declares the SPI work CLI-version-neutral, P20's stated reason targets M1-849's probe, and the user directed the start; no in-flight tick ticket exists so no module overlap"
escalation_reason:
---

# M1-847: Add a streaming generate shape to the LlmProvider SPI

## Context

The I1 streaming feature needs a token/chunk source, and none exists:
`LlmProvider.generate` is single-string (LlmProvider.java:36), none of the
three impls parses SSE, and M1-607 deferred exactly this as "a separate,
larger decision". This ticket is the pure-work SPI slice — it adds the
streaming call shape and proves it against fake endpoints, changes no
shipped behavior, and contradicts no spec text (the SPI Java surface is
design-tier, llm.md:614-630). The display policy that governs its only
consumer is M1-846's amendment; the consumer itself is M1-849. Shared
analysis: `analysis_ref:`.

## Root cause

The SPI was deliberately minimal (LlmProvider.java:9-16); streaming was
never added because the only consumer (the stage-label notifier) never
needed chunks. The work is bounded and verified-absent: request assembly
(Jackson bodies), the JDK HttpClient transport, the breaker/metrics/budget
decorator chain, and the router all exist and are single-shot; each needs
its streaming mirror with the controls carried across (§10).

## Pitfalls

Numbered per the analysis document; this ticket carries P11 (SPI half),
P12, P15, P20.

- P11 (SPI half): interrupt posture — a stream read on an interrupted
  virtual thread must abort the call cleanly; the M1-763 record shows
  armed interrupts vs. blocking I/O are a live hazard on virtual threads.
  (The /stop terminal semantics are M1-849's half.)
- P12: decorator-stack controls ride the stream — breaker classification of
  MID-STREAM failures, the accumulated-body cap, the terminal usage-frame
  boundary checks, config-sourced metric labels, and the D56 credential
  coupling (the streaming call resolves the same endpoint/key the
  single-string call would).
- P15: pin only the SPI contract — the chunk-consumer shape, completion/
  failure semantics, capability signal. Nothing the M1-849 consumer would
  have to contradict.
- P20: soft sequencing — start after the M1-819..843 batch lands; the SPI
  work is CLI-version-neutral (the live-message grammar is unchanged in
  v7.0.0, live-text-streaming.md:22-38).

## Approach

- **Files to touch:** `files_scope` (the file fan-out is the decorator
  chain — each file's change is its streaming mirror; BudgetedLlmProvider
  needs one only if the per-call accounting shape differs, the implementor
  confirms at start).
- **Steps, in order:**
  1. Write the reproduction RED against a fake SSE endpoint (the repo's
     provider-test posture).
  2. Add the streaming shape: an explicit capability signal (no silent
     assumption — a provider either streams or reports it cannot) plus the
     chunk/terminal consumer contract. Decide the exact Java shape at
     start against the SPI-minimalism javadoc (a sub-interface keeps
     `LlmProvider`'s single-method surface; a default method that throws
     is the trap to avoid).
     [Start decision 2026-08-15: the members live ON `LlmProvider` as
     `supportsStreaming` (default `false`, the explicit cannot-stream
     report) + `generateStreaming` (default refuses, unreachable past
     the signal). The sub-interface was tried first and is CDI-illegal
     for this architecture: the decorator chain decorates the
     `LlmProvider` type, and a decorated method must be a member of it
     — with the shape on a sub-interface, either the decorators'
     delegate violates CDI's delegate-type rule (DefinitionException,
     caught by the collector module's boot in the first full verify)
     or streaming calls bypass the wrappers entirely, dropping the
     breaker/metered/budget controls. The trap is avoided by the
     signal, not the shape: callers gate on `supportsStreaming` via
     the router, so no caller reaches the refusing default unaware.]
  3. Implement SSE parsing in OpenAiCompatibleProvider (DeepSeek inherits)
     and AnthropicProvider, through LlmHttpSupport's shared transport so
     the body cap and breaker classification live in one place.
  4. Wrap the decorators (breaker, metered) with the P12 controls.
  5. Surface the capability through LlmRouter and extend the startup
     assertion scan.
- **Controls to preserve (§10):** the breaker classification rules
  (transport vs. application), the 1-8 MiB body cap posture, the usage
  boundary checks, per-task metrics with config-sourced labels, the D56
  credential coupling, the per-task timeout posture — enumerated here so
  they are not improvised at implementation time.
- **Pitfall→mitigation:** P11→step 3's interrupt test; P12→step 4 +
  acceptance items 3-5; P15→acceptance item 8; P20→the scheduling note.

## Definition of done

The streaming shape exists with an explicit capability signal; both wire
dialects parse it; the decorator chain wraps it with breaker/cap/usage/
metrics controls intact; timeout and interrupt postures are tested; no
pre-existing test changes; no consumer exists yet; mvn verify is green.

## Verification

- P11 → StreamingInterruptTest — feeds an interrupted virtual thread a
  stalled stream and asserts the call aborts without a hang.
- P12 → CircuitBreakingLlmProviderStreamingTest (mid-stream drop trips the
  breaker; mid-stream application error does not), StreamingBodyCapTest
  (over-cap stream cut, partial discarded), the usage-frame test
  (impossible counts discarded whole), MeteredLlmProviderStreamingTest
  (config-sourced labels).
- P15 → acceptance item 8 — the pre-existing suite runs unchanged.
- P20 → the ticket record notes the batch landed before start.
- failure mode → items 2 (malformed frame fails the call, never a
  synthetic chunk), 3 (mid-stream drop), 4 (over-cap, lying usage) and 6
  (stall, interrupt) are the hostile-input coverage.
- acceptance item 9 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: every consumer (M1-849), any user-visible change
or spec edit, the single-string generate contract and its callers, and
stream retry/resume semantics. If a provider's real endpoint turns out to
frame SSE differently than its documented shape (a live-observed fact, not
knowable from this checkout), the implementor adapts the parser to the
observed frames and records the observation in the ticket — the fake
endpoint then mirrors the OBSERVED shape, not the documented one.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-847-streaming-translation-switch-4.md
```
