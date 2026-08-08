---
id: M1-802
title: "ComfyUI client: server-built graph, bounded fetch, cancel"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  to-be-written: ComfyUIClientTest.promptLandsInExactlyOneGraphStringField —
  the intended test builds the workflow graph for a prompt containing JSON
  metacharacters (quotes, braces, template-looking text), serializes, re-parses,
  and asserts the prompt appears as exactly one string VALUE
  (CLIPTextEncode.text) and nowhere else in the document; it cannot compile
  today because no image-backend client exists (grep-verified: no
  infochat.image.* key and no ComfyUI reference anywhere in Java sources).
  `start` writes the test and runs it RED before any fix code (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/image-generation-feature.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/image/
  - infochat-provider/src/test/java/app/zcat/infochat/provider/image/
  - docs/design/future/image-generation.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The container/overlay the live probe runs against (M1-797 — this
    ticket's unit tests use a stub HTTP server; the live no-retention probe
    is named here and runs once M1-797's container exists).
  - The spool, PNG strip, and OutboundDelivery path that consume the
    fetched bytes (M1-801).
  - The /image command, credit/cooldown gates, and the translation leg
    (M1-803 — this ticket exposes the primitives: queue depth, submit,
    poll, cancel, fetch, history clear).
  - Any ImageGenerationProvider SPI or new Maven module (design decision
    13: one class in the Provider until a second backend exists).
  - Widening LlmHttpSupport's package-private helpers for reuse (analysis
    D-2: they are String-shaped and package-private; this client carries
    its own bounded byte read — §3: the widening is a proposal, not a
    rider).
acceptance:
  - "ComfyUIClientTest.promptLandsInExactlyOneGraphStringField passes — REPRODUCTION (written and run RED at start). The graph is built SERVER-SIDE from a template plus a JSON serializer (commands.md:631-632; analysis P15): string interpolation into graph JSON is an RCE vector on the GPU box (D77: the endpoint accepts whole executable workflow graphs)."
  - "ComfyUIClientTest.overCapResponseBodyIsRefusedBeforeAnyBytesAreRetained passes — FAILURE-MODE (analysis P7, commands.md:633-635): a stub endpoint streaming past the profile-driven byte cap is cut off at the cap; the partial body is discarded, never spooled (endpoint-chosen bytes, security.md §Trust boundaries item 9)."
  - "ComfyUIClientTest.timeoutCancelsTheBackendJob passes — FAILURE-MODE (commands.md:613-615; analysis P9): when the poll exceeds the timeout the client issues the cancellation call (the stub asserts it arrived); an abandoned job keeps burning GPU. The exact cancel endpoint (`POST /interrupt` vs queue delete) is an ASSUMPTION to verify against the running backend (analysis D-4; the design says 'verify the endpoint at design time')."
  - "ComfyUIClientTest.queueDepthIsReadFromTheBackend passes: the queue-depth gate primitive reads the backend's /queue and reports depth (the gate decision itself is M1-803's); shape verified against the live API (ASSUMPTION, D-4)."
  - "SHIP-BLOCKER (D75 backend no-retention acceptance check, Provider half): after a completed job the client clears the backend's submitted-graph history (clear mechanism an ASSUMPTION to verify, D-4), and the ticket ships a runnable probe script that, against M1-797's container, performs a full job with a canary prompt and asserts: GET /history contains no prompt text AND no leftover output files remain backend-side (the container's tmpfs+janitor half is M1-797). The probe is the implementing change's acceptance check D75 demands — 'query the backend's history after a job; assert no prompt text, no leftover output files'."
  - "The SSRF posture is recorded (analysis P8): docs/design/future/image-generation.md notes that infochat.image.base-url is operator-configured internal infrastructure outside the security.md §SSRF enumeration (feeds/redirects/StreamSource//add-source probes), which is WHY the call does not pass through infochat-ssrf (the gate would block the loopback/private address the feature requires); the compensating control is item 9's bounded-read posture (item 2 above) — Verify: `grep -n 'infochat-ssrf' docs/design/future/image-generation.md` shows the recorded exemption."
  - "New infochat.image.* client keys (base-url, timeouts, byte cap) are documented in the design doc — Verify: `grep -n 'infochat.image.' docs/design/future/image-generation.md` lists each new key, and DocumentedConfigKeyParityTest passes via `mvn verify` (analysis P16; scripts/lint-config-keys.py was checked and does NOT gate here — it covers required-key base declarations and excludes infochat-provider by default; base-url is Optional for the D73 config gate and the rest carry declared defaults)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/image/ComfyUIClientTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/decisions.md (D75, D77)
  - docs/spec/security.md §Trust boundaries
decision_refs:
  - D75
  - D77
---

# M1-802: ComfyUI client: server-built graph, bounded fetch, cancel

## Context

The /image flow's backend leg: submit a server-built workflow graph, gate on
queue depth, poll /history, cancel on timeout, fetch the image bytes under a
cap, and leave the backend with no retrievable prompt (D75's verifiable end
state). Nothing exists (grep-verified: no ComfyUI reference in Java). One
class in the Provider, plain HTTP — design decision 13 rejects an SPI/module.
Shared analysis: `analysis_ref:`.

## Root cause

Feature gap. The privacy-critical facts are spec-level: the endpoint is code
execution on the hosting box (D77), so the graph is built server-side with
user text in exactly one JSON string field (commands.md:631-632); the
backend's replies are endpoint-chosen bytes under a byte cap
(commands.md:633-635); and D75 makes the no-retention end state an
acceptance check, not a process note.

## Pitfalls

Numbered consistently with the analysis document.

- P15: graph injection = RCE. Build via a JSON serializer (Jackson), user
  text as one string value; never string-interpolation.
- P7: bounded byte read implemented HERE — LlmHttpSupport's helpers are
  package-private and String-shaped (discrepancy D-2); do not widen them as
  a side quest (§3).
- P8: no infochat-ssrf routing — record the configured-internal exemption
  (the gate is scoped to user-controlled URLs and would block loopback).
- P9: timeout cancels, never abandons; the cancel endpoint shape is an
  ASSUMPTION to verify (D-4).
- P24: the post-job history clear + the live no-retention probe are THIS
  ticket's Provider-side half of the D75 acceptance check; the clear
  mechanism is an ASSUMPTION to verify (D-4).
- P4: no log line on any path (error, timeout, breaker) may carry the
  prompt or the graph — SafeLog discipline; exception messages from HTTP
  failures can quote request/response bodies.
- P16: every new `infochat.image.*` @ConfigProperty key must be documented
  in the design doc or exempted — DocumentedConfigKeyParityTest (M1-708)
  gates the documented-vs-real key sets.

## Approach

- **Files to touch:** `files_scope` (new provider/image client + tests +
  design notes).
- **Steps, in order:**
  1. Verify the live API shapes against a running ComfyUI (from M1-797's
     container when available, else the conda spike env): /queue shape,
     cancel endpoint, history-clear mechanism. Record in the design doc.
  2. `ComfyUIClient`: config (`infochat.image.base-url`, timeouts, byte
     cap), graph builder (template + serializer, one string field), submit,
     queue-depth read, poll with timeout→cancel, bounded `/view` fetch
     returning bytes to the caller (the spool write is M1-801's caller
     side), post-job history clear.
  3. Stub-server tests (JDK HttpServer) for every acceptance item; the
     breaker pattern follows LlmCircuitBreakerRegistry reuse if it fits
     without llm-adapter changes — else the client's own failure counting,
     stated in the design doc.
  4. The live no-retention probe script + design-doc SSRF exemption note.
- **Controls to preserve (§10):** none rerouted — new code. The llm-adapter
  module is untouched (D-2 discipline).
- **Pitfall→mitigation:** P15→step 2 + item 1; P7→step 2's own bounded
  reader + item 2; P8→step 4's recorded exemption; P9→step 2's
  timeout→cancel + item 3; P24→steps 1/4 + item 5; P4→SafeLog on every
  failure arm (review grep: no prompt/graph string reaches a log call);
  P16→step 4's key documentation + item 7.

## Definition of done

The five ComfyUIClientTest methods pass against the stub; the cancel and
clear mechanisms are verified against the real backend and recorded; the
live no-retention probe exists and passes against M1-797's container; the
SSRF exemption and config keys are documented; full verify green.

## Verification

- P15 → the reproduction test (metacharacter prompt, re-parsed graph, one
  string value) — a builder interpolating into JSON fails it.
- P7 → .overCapResponseBodyIsRefusedBeforeAnyBytesAreRetained
  (failure-mode: hostile stub streaming garbage).
- P9 → .timeoutCancelsTheBackendJob (stub asserts the cancel call arrived).
- P24 → the acceptance-item-5 probe (canary prompt; /history and output-dir
  assertions) + the stub test for the clear call.
- P8/P16 → the acceptance items 6-7 design-doc greps +
  DocumentedConfigKeyParityTest via `mvn verify`.
- P4 → review-time grep over the diff: no log statement interpolates the
  prompt, graph, or response body.
- Non-vacuity: a client that polls-then-abandons fails item 3; one that
  reads unbounded fails item 2; one that skips the history clear fails the
  item-5 probe.

## Out-of-scope

Named in `out_of_scope`: the container (M1-797), spool/strip/delivery
(M1-801), the command and its gates (M1-803), any SPI/module split, any
llm-adapter change. No pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-802-comfyui-client-graph-builder.md
```
