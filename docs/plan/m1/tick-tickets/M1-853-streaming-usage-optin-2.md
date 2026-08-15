---
id: M1-853
title: "Land the observed streaming usage opt-in request shape"
status: pending
created: 2026-08-15
last_updated: 2026-08-15
flow: tick
reproduction: >-
  to-be-written: OpenAiCompatibleProviderStreamingTest#aStreamingRequestCarriesTheDecidedUsageOptIn
  (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/streaming-usage-optin.md). The marker converts
  at start, AFTER M1-852's record fixes the decided shape (workflow §0):
  the test drives generateStreaming against the SseMockServer fake whose
  frames mirror the OBSERVED with-flag wire shape from the record, then
  asserts on the captured request body (SseMockServer.receivedBodies(),
  :70-72) that the wire request carries exactly the usage opt-in the record
  decided — written and run RED first (the field is absent today,
  OpenAiCompatibleProvider.java:275-277 assembles only stream:true), green
  after. Asserted on the WIRE BODY, never a config value (the M1-746
  temperature-on-the-wire precedent, OpenAiCompatibleProvider.java:46-48).
analysis_ref: docs/plan/m1/tick-analysis/streaming-usage-optin.md
blocked_by: [M1-852]
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderStreamingTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY new infochat.* config key and ANY base-url dialect sniffing (analysis
    P5/P6: the §7 feature-flag tension, the DocumentedConfigKeyParityTest
    surface, and the operator-config-adjacent sniff). If M1-852's record
    mandates a split neither the unconditional shape nor the existing
    provider-entry/customizeRequestBody seam can express, STOP and escalate —
    a key is a user decision, never an implementation convenience.
  - >-
    ANY change to the single-string generate contract, the decorator chain
    (breaker/metered/budget), the usage boundary checks (MeteredLlmProvider.
    plausibleUsage is the sole gatekeeper), or the SSE transport
    (LlmHttpSupport) — the stream branch of assembleBody is the ONLY
    production surface.
  - >-
    Any consumer wiring (M1-849's) and any docs/spec/** edit — the request
    wire shape is design-tier (llm.md §What lives in design notes, :625-641);
    the observability commitment already supports asking for usage.
  - >-
    Any behavioral change for Anthropic — no opt-in exists in that dialect
    (usage halves ride message_start/message_delta,
    AnthropicProvider.java:355-380); M1-852's control leg expected it clean,
    and a recorded deviation goes through its own adaptation decision.
acceptance:
  - "OpenAiCompatibleProviderStreamingTest.aStreamingRequestCarriesTheDecidedUsageOptIn (the reproduction, converted from to-be-written and run RED at start) passes — the captured streaming request body carries exactly the shape M1-852's record decided (unconditional stream_options.include_usage=true in the shared stream branch, or the record's narrowest mechanism on the seam it names), with the fake's frames mirroring the OBSERVED with-flag wire shape — including any empty-choices usage frame or non-terminal usage placement the record observed (M1-847's adaptation clause: the fake mirrors the OBSERVED shape, not the documented one; analysis P2/P9)."
  - "The DeepSeek inheritance extension passes — deepSeekInheritsTheStreamingShape's body assertions (OpenAiCompatibleProviderStreamingTest:100-104) additionally assert the decided opt-in rides the subclass's streaming request alongside the thinking field: the customizeRequestBody seam is untouched, one branch in the parent serves both (analysis P1's fleet fact, now the pin)."
  - "The single-string request stays byte-shape-identical: a non-streaming generate call's captured body carries NEITHER stream NOR stream_options — FAILURE-MODE (analysis P7): the field escaping the stream=true branch fails this pin; asserted on a captured body (OpenAiCompatibleProviderTest's receivedBodies seam, :49/:67-72 — the field-ABSENT assertion pattern at :258 is the precedent)."
  - "OpenAiCompatibleProviderStreamingTest.streamWithNoUsageFrameCompletesWithNullUsage (the honest-gap failure-mode test, added by this ticket) passes — a fake stream with NO usage frame at all (the observed without-flag shape for opt-in-required backends) completes: chunks delivered, text assembled, usage() null, no exception — pinning the spec's no-usage state (llm.md:605-607: the call counts as reporting no usage) and that nothing synthesizes a figure (analysis P8; MeteredLlmProvider.java:236-243 invents nothing)."
  - "Every pre-existing provider/decorator/router test passes UNCHANGED — the full M1-847 suite (streaming, body-cap, timeout, interrupt, breaker, metered incl. impossibleTerminalUsageIsDiscardedWhole, router capability) plus the single-string and DeepSeek suites run as-is (§10; this ticket authorizes no modification to any pre-existing test)."
  - "No new infochat.* config key enters the diff (analysis P5): DocumentedConfigKeyParityTest's documented-key surface is unchanged and green in mvn verify — the decided shape is hard-coded on the wire request, the M1-746 temperature precedent (OpenAiCompatibleProvider.java:46-48, :272-274)."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderStreamingTest.java
      (existing class GAINS the reproduction method, the observed-shape fake
      frames, and the honest-gap failure-mode method).
    - >-
      The single-string no-field pin (acceptance item 3), added to
      OpenAiCompatibleProviderTest via its existing receivedBodies seam.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/spec/security.md §Trust boundaries
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
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-853: Land the observed streaming usage opt-in request shape

## Context

M1-852's record observed the fleet (backend × with/without
`stream_options.include_usage`) and its locked rule decided the request
shape. Today the streaming request carries only `stream: true`
(OpenAiCompatibleProvider.java:275-277), so against real OpenAI/DeepSeek —
and any backend that gates its usage frame on the opt-in — streamed calls
return `usage() == null`: `llm.calls.total` advances while `llm.tokens.in/out`
record nothing (MeteredLlmProvider.java:235-243 names the gap shape), the
silent-meter state the M1-847 round-1 review flagged as
RECOMMENDED-NEW-TICKET, DECIDE-BEFORE M1-849's host live-validation probe
(tick-review-M1-847-r1.txt:76-97). This ticket lands the decided shape and
pins it by mirroring the OBSERVED wire shapes in the fakes. Shared analysis:
`analysis_ref:`.

## Root cause

Verified: the stream branch of `assembleBody` adds no usage opt-in
(OpenAiCompatibleProvider.java:275-277 — a repo-wide grep for
`include_usage|stream_options` returns nothing), the parser and metering
stack correctly consume-or-ignore usage depending on what the endpoint
sends (StreamingParser :401-414; MeteredLlmProvider :253-255), and every
in-tree fake emits usage (OpenAiCompatibleProviderStreamingTest:55-56,
:82-83) — so the omission was invisible to the suite that shipped it. What
shape to send instead was a live-observed fact M1-852 has now recorded;
this ticket is the execution of that record, not a new decision.

## Pitfalls

Numbered per the analysis document; this ticket carries P1 (code half),
P2, P5, P6, P7, P8, P9.

- P1 (code half): one `assembleBody` serves six providers — the landed field
  rides all of them (OpenAiCompatibleProvider.java:28-31; DeepSeek subclass
  :62). The M1-852 record is the authority that it is tolerated; if the
  record mandated a narrow mechanism, THIS ticket lands that seam, not the
  unconditional field.
- P2: wire-request assertions, not config or parser-side only — the field
  must be asserted on the captured body (`receivedBodies()`), and a test
  re-feeding an always-usage fake without asserting the request proves
  nothing (non-vacuity, engineering-rules §8).
- P5: no config key — the decided shape is hard-coded on the wire request
  (the M1-746 temperature precedent); a key is the escalated, user-approved
  shape only.
- P6: no base-url sniffing — if the record split the fleet, the existing
  provider-entry/subclass seam expresses it; operator config strings never
  drive wire dialect.
- P7: fixtures calibrated to the END state — the fakes mirror the OBSERVED
  shapes (with-flag shape on the reproduction; the no-usage-frame shape on
  the honest-gap test), never the pre-ticket always-usage blindness.
- P8: nothing fabricates usage — usage arrives from the endpoint's terminal
  report or not at all; the boundary checks in `plausibleUsage` are the
  unchanged gatekeeper (trust boundary 9, security.md:110-132).
- P9: observed-beats-documented — if the record observed a usage frame
  shape the parser does not yet expect (empty-choices terminal frame,
  non-terminal placement), the parser adapts and the fake mirrors that
  exact shape (M1-847's adaptation clause, ticket :214-218).

## Approach

- **Files to touch:** `OpenAiCompatibleProvider.java` (the stream branch of
  `assembleBody` + the wire-shape javadoc block — §11: touching the body
  means the javadoc's request-body example states the NEW truth) and
  `OpenAiCompatibleProviderStreamingTest.java` (reproduction + observed-shape
  fakes + honest-gap test); the single-string no-field pin rides
  `OpenAiCompatibleProviderTest`'s existing body-capture seam. If the record
  mandated a narrow mechanism, the subclass/provider-entry files it names
  join `files_scope` — the record's decision section is the allowlist.
- **Steps, in order:**
  1. Read M1-852's decision section; confirm the mandated shape and any
     observed frame shapes to mirror. A contradiction between the record and
     this ticket's assumption ESCALATES (out_of_scope).
  2. Convert the reproduction marker: write the wire-body test against the
     OBSERVED with-flag fake shape, run it RED.
  3. Land the field (or the record's narrow mechanism) in the stream branch;
     update the wire-shape javadoc.
  4. Extend the DeepSeek inheritance assertions and add the single-string
     no-field pin and the honest-gap failure-mode test.
  5. Full `mvn verify`.
- **Controls to preserve (§10):** single-string body byte-shape-identical;
  the subclass seam still rides the stream; decorator-chain controls
  (breaker classification, body cap, usage boundary checks, config-sourced
  labels, D56 credential coupling, per-task timeout) untouched with their
  M1-847 tests green unchanged; `plausibleUsage` remains the sole gatekeeper
  for what reaches counters.
- **Pitfall→mitigation:** P1→step 1 + the record as allowlist; P2→step 2's
  wire assertion; P5/P6→the out_of_scope bars; P7→step 2/4's mirrored
  fakes; P8→nothing added — the boundary checks unchanged and pinned by
  MeteredLlmProviderStreamingTest; P9→step 2's mirror clause.

## Definition of done

The decided request shape is on the wire for every streaming call of the
OpenAI-compatible dialect (DeepSeek inheriting it alongside its thinking
field); the single-string body is unchanged; the fakes mirror the OBSERVED
shapes; the honest no-usage state is pinned (null usage, call completes,
nothing synthesized); the full M1-847 suite and every pre-existing test run
unchanged; no config key; mvn verify is green.

## Verification

- P1 → the reproduction's wire assertion + the DeepSeek inheritance
  extension (acceptance items 1-2) — the field provably rides the shared
  body and the subclass seam.
- P2 → acceptance item 1 asserts on `receivedBodies()`; the mutation "remove
  the field from assembleBody" fails it.
- P5 → acceptance item 6 — the parity surface unchanged in a green
  `mvn verify`.
- P6 → nothing to test (rejected option; cited in the commit message's
  Alternatives).
- P7 → acceptance item 3 (mutation "field outside the stream branch" fails
  the single-string pin) and item 4 (the no-usage-frame fake pins the end
  state).
- P8 → acceptance item 4's no-synthesis pin + the UNCHANGED
  MeteredLlmProviderStreamingTest.impossibleTerminalUsageIsDiscardedWhole
  (:91-112).
- failure mode → acceptance item 4, named explicitly:
  OpenAiCompatibleProviderStreamingTest.streamWithNoUsageFrameCompletesWithNullUsage
  feeds the no-usage-frame stream (the OBSERVED without-flag shape — the
  hostile input this suite never had) and asserts the protected behavior:
  chunks delivered, text assembled, call completes, usage() null, nothing
  synthesized — a regression that throws, drops chunks, or invents a
  figure on the usage-less stream fails it.
- P9 → acceptance item 1's mirror clause — any observed-shape adaptation
  lands with the fake asserting that exact frame shape.
- acceptance item 5 → the unchanged pre-existing suite; item 7 → `mvn
  verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: no config key and no base-url sniffing (escalate if
the record mandates what the existing seams cannot express); no change to
the single-string contract, the decorator chain, the boundary checks, or the
SSE transport; no consumer wiring (M1-849); no spec edit; no Anthropic
behavior change. This ticket modifies NO pre-existing test — the
DeepSeek-inheritance and single-string assertions EXTEND existing classes'
method bodies where the template shows, and any pre-existing-test edit
beyond that named extension is an engineering-rules §8 violation.

## Census

Not class-scoped: the defect has exactly one production site (the stream
branch of the OpenAI-compatible `assembleBody` — the Anthropic dialect has
no opt-in, verified AnthropicProvider.java:220-222, :355-380). No census
required.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-853-streaming-usage-optin-2.md
```
