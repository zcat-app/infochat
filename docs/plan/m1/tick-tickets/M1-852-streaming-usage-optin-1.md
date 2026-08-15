---
id: M1-852
title: "Observe streaming usage opt-in across the LLM fleet"
status: pending
created: 2026-08-15
last_updated: 2026-08-15
flow: tick
reproduction: >-
  Probe: `grep -rn 'include_usage\|stream_options' docs/ infochat-llm-adapter/`
  returns nothing — no in-tree code, test, or record anywhere touches the
  streaming usage opt-in, while the streaming request ships without it
  (OpenAiCompatibleProvider.java:275-277 adds only stream:true) and the
  M1-847 fakes emit usage unconditionally
  (OpenAiCompatibleProviderStreamingTest:55-56) — the suite is green by
  construction against a request shape no real endpoint has confirmed.
  Observed evidence gap: the M1-847 round-1 review's RECOMMENDED-NEW-TICKET
  names usage()==null on the real wire and DECIDE-BEFORE M1-849's probe
  (.opencode/worktrees/M1-847/.scratch/tick-review-M1-847-r1.txt:76-97), and
  zero cells of that decision (backend x with/without flag) have ever been
  observed — the only "evidence" is API documentation outside this repo.
analysis_ref: docs/plan/m1/tick-analysis/streaming-usage-optin.md
blocked_by: []
files_scope:
  - docs/measurement/streaming-usage-optin.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code change or docs/spec/** edit. This ticket produces
    evidence and the decision; the request shape lands in M1-853. No verdict
    here is a direction by itself (translator-slot.md's standing rule:
    evidence justifies a row; it never appears inside one).
  - >-
    COMMITTING the .bench working captures (gitignored, the M1-850/lang-quality
    posture). Only the promoted record lands at
    docs/measurement/streaming-usage-optin.md.
  - >-
    QUALITY measurement of any kind — output quality, latency benchmarks,
    token accounting accuracy. This is wire-shape observation only: did the
    endpoint error, and did usage arrive.
  - >-
    The NON-streaming path: single-string replies carry usage in the same body
    unconditionally (OpenAiCompatibleProvider.java:49-54) — no observation
    needed; out of the matrix's question.
  - >-
    ANTHROPIC PARSER CHANGES: its leg is the free control (no opt-in exists in
    that dialect, AnthropicProvider.java:220-222, :355-380); a deviation is
    RECORDED and handed to M1-853's adaptation scope, never edited here.
acceptance:
  - "The decision rule is locked and committed BEFORE any cell runs (the M1-850 pre-registration posture, early-commit arm): the record at docs/measurement/streaming-usage-optin.md opens with the rule — per (backend[, model], flag-state) cell, record error-or-not (HTTP status) and usage-present-or-not (and which frame the usage rode); the fleet shape is UNCONDITIONAL stream_options.include_usage=true iff EVERY tested with-flag cell is error-free AND no with-flag cell LOSES usage its without-flag cell had; any erroring or usage-losing backend demotes to the narrowest correct mechanism (the existing provider-entry/customizeRequestBody seam, never base-url sniffing — analysis P6) scoped to exactly the offending backends; NOT-OBSERVED cells are residuals, never vetoes — probe: `grep -n -i 'decision rule' docs/measurement/streaming-usage-optin.md` shows the locked rule, and the record's status line names the protocol-first commit order (the M1-850 round-1 lesson: a verbatim-claim status line must name any results-touching exception)."
  - "Every cell of the backend x flag matrix is recorded — OpenAI, DeepSeek, Ollama (its /v1 OpenAI-compat route), llama.cpp (llama-server compat route), OpenRouter, NanoGPT, each observed WITHOUT and WITH stream_options.include_usage=true on the same prompt: HTTP status (error-or-not, with the error body's shape summarized — never echoed if it carries prompt fragments), usage-present-or-not, and the verbatim observed frame shape the usage rode (terminal data frame / message halves / other) — probe: `grep -n '| ' docs/measurement/streaming-usage-optin.md` shows one matrix row per (backend, flag-state) cell with those columns. FAILURE-MODE posture: an erroring or usage-losing with-flag cell is a RESULT that demotes the shape (item 1's rule) — recorded, never retried away, dropped, or averaged into a verdict."
  - "Gateway cells are PER-MODEL records, not backend verdicts: each OpenRouter and NanoGPT row names the model id probed, and the record's residuals paragraph states explicitly that gateway behavior is upstream-dependent and does not generalize across models (analysis P3) — probe: `grep -n -i 'residual' docs/measurement/streaming-usage-optin.md` shows the gateway-scope paragraph naming every probed model id."
  - "The Anthropic control leg: one streamed call with the production streaming request (no opt-in exists in that dialect) confirms the parser against reality — the input half observed on message_start, the output half on message_delta, usage() populated end to end; no fix expected; any deviation is recorded verbatim and named as input to M1-853's adaptation scope (M1-847's out-of-scope clause: the fake mirrors the OBSERVED shape) — probe: `grep -n -i 'anthropic' docs/measurement/streaming-usage-optin.md` shows the control row with both usage halves."
  - "A cell that cannot run on this box (no credential, no local service up — cell runnability is a start-time fact, recorded not assumed) is recorded NOT-OBSERVED with its reason — never blank, never inferred from documentation — probe: `grep -n 'NOT-OBSERVED' docs/measurement/streaming-usage-optin.md` shows every such row with its reason (zero rows only if every cell actually ran)."
  - "The repo commit the runs executed against and the harness location (.bench/, gitignored) are pinned in the record (the measured-surfaces-are-moving rule, translator-slot.md:69-71 — the M1-850 posture) — probe: `grep -n 'commit' docs/measurement/streaming-usage-optin.md` shows the pin."
  - "The record closes with the DECISION the locked rule produces: unconditional, or narrowest-mechanism naming each offending backend and the exact seam it rides — and names every NOT-OBSERVED backend as a residual that M1-849's live probe re-checks against the deployment's actual endpoint — probe: `grep -n -i '^## ' docs/measurement/streaming-usage-optin.md` shows the closing decision section."
  - "mvn verify from repo root is green (evidence-only ticket; the build must not regress, engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      The harness lives under .bench/ (gitignored), the M1-850 posture; the
      promoted record is the only committed artifact, so there is no JUnit
      surface to add. mvn verify covers the no-regression leg.
spec_refs:
  - docs/spec/llm.md §Bounded concurrency and observability
  - docs/spec/security.md §Trust boundaries
decision_refs:
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

# M1-852: Observe streaming usage opt-in across the LLM fleet

## Context

The M1-847 streaming SPI ships a request that opts into a stream but never
into usage reporting: `assembleBody` adds only `"stream": true`
(OpenAiCompatibleProvider.java:275-277). OpenAI and DeepSeek document that
the terminal SSE usage frame arrives only with
`stream_options: {"include_usage": true}` — the M1-847 round-1 review's
RECOMMENDED-NEW-TICKET finding (tick-review-M1-847-r1.txt:76-97) — so
against real cloud endpoints streamed calls return `usage() == null`:
`llm.calls.total` advances while `llm.tokens.in/out` record nothing, the
exact silent-meter gap the spec's discard-whole rule relies on being able to
see. The decision is fleet-wide (one bean serves OpenAI, DeepSeek, OpenRouter,
NanoGPT, Ollama, llama.cpp — OpenAiCompatibleProvider.java:28-31), the repo's
recorded belief is that unknown body fields have hard-400'd strict backends
(DeepSeekProvider.java:26-32), and the local backends are the D56 default
topology — so the shape is decided from observation, not assumption, BEFORE
M1-849's host live-validation probe runs its LLM leg. This ticket produces
the observation matrix; the code lands in M1-853. Shared analysis:
`analysis_ref:`.

## Root cause

An evidence gap, not a code defect (the code defect is M1-853's): the
request-shape question "does each fleet member stream usage with/without the
opt-in, and does it tolerate the field at all" has zero observations. The
in-tree fakes always emit usage (OpenAiCompatibleProviderStreamingTest:55-56,
:82-83; AnthropicProviderStreamingTest:53, :68), so the suite is green by
construction and cannot inform the decision; the documented behavior lives
in external API docs this repo cannot verify. A repo-wide grep for
`include_usage|stream_options` returns nothing — nothing observed, nothing
decided, nothing contradicted.

## Pitfalls

Numbered per the analysis document; this ticket carries P1 (evidence half),
P3, P4.

- P1 (evidence half): the decision the record feeds rides all six providers —
  the matrix is the only thing standing between the fleet and a blind
  unconditional field (the 400 hazard, DeepSeekProvider.java:26-32) or a
  blind refusal (the silent gap). (The code half is M1-853's.)
- P3: gateway asymmetry — OpenRouter and NanoGPT front many upstream models;
  a cell is a per-(gateway, model) record with stated residuals, never a
  backend-wide verdict.
- P4: matrix discipline — locked decision rule before runs (the M1-850
  pre-registration posture), verbatim observed frame shapes, commit pin, and
  NOT-OBSERVED rows with reasons; a blank cell reads as "no usage", the
  exact ambiguity this work exists to close.

## Approach

- **Files to touch:** `docs/measurement/streaming-usage-optin.md` (new, the
  promoted record); the harness lives under `.bench/` (gitignored) per the
  M1-850 precedent.
- **Steps, in order:**
  1. Write and LOCK the decision rule (acceptance item 1), then commit the
     protocol+rule before any cell runs (the M1-850 early-commit arm).
  2. Enumerate the cells and their runnability on this box (which local
     services can stand up; which remote keys exist) — record NOT-OBSERVED
     with reasons for the rest before probing anything.
  3. Run each runnable cell pair (without flag, with flag) on the same
     prompt through the production streaming request shape
     (`stream:true` ± `stream_options`), capturing the raw SSE transcript
     under `.bench/`; record status / usage-present / frame shape per cell.
  4. Run the Anthropic control leg (one streamed call; both usage halves).
  5. Pin the commit; write the residuals paragraph; apply the locked rule
     and state the decision.
- **Controls to preserve (§10):** none rerouted — no code path changes. The
  record's own integrity rules (locked rule, commit pin, NOT-OBSERVED
  disclosure, verbatim shapes) are the controls.
- **Pitfall→mitigation:** P1→step 5's rule application; P3→step 3's per-model
  gateway rows + step 5's residuals; P4→steps 1-2 and the probes.

## Definition of done

The committed record carries: the locked decision rule (committed before any
results), one matrix row per (backend[, model], flag-state) cell with
error-or-not and usage-present-or-not plus the observed frame shape, per-model
gateway rows with a residuals paragraph, the Anthropic control row, NOT-OBSERVED
rows with reasons, the commit pin, and the closing decision — each verifiable
by its named grep probe — and mvn verify is green.

## Verification

- P1 → item 1's locked rule + item 7's decision — the demotion clause is
  what turns an erroring cell into a shape change instead of a broken fleet.
- P3 → item 3's per-model rows and residuals probe.
- P4 → items 1 (rule locked first), 5 (NOT-OBSERVED rows), 6 (commit pin) —
  the record's own gates, each with a grep probe.
- failure mode → item 2's posture: an erroring or usage-losing with-flag cell
  stays in its cell as a RESULT that demotes the shape — never dropped,
  retried away, or averaged.
- acceptance item 8 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: no code or spec change (M1-853's), no committed
.bench captures, no quality/latency measurement (wire-shape observation
only), no non-streaming observation, no Anthropic parser edit (control leg;
a deviation records and hands to M1-853). If NO cell can run at start (no
keys, no local services), STOP and escalate with the NOT-OBSERVED record —
do not fabricate cells from documentation; the M1-849 probe cannot be
pre-gated on an empty matrix.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-852-streaming-usage-optin-1.md
```
