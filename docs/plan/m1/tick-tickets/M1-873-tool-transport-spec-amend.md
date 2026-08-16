---
id: M1-873
title: "Record the tool-call transport architecture in spec"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe (evidence/spec ticket; no test can exist for an absent rule —
  the M1-858 precedent): grep -n 'transport' docs/spec/llm.md returns
  NO match, and llm.md's only 'tool' hits are the recall tool,
  helpLookup, and trace ids (:282, :493-496, :653) — the spec is silent
  on how a tool call travels between the model and the dispatch
  boundary. The layered transport M1-872 implements (text protocol as
  universal fallback; a structured tools wire shape under DETECTED
  endpoint support; identical validation on every transport) therefore
  has no spec-level record: a future diff could narrow the fallback,
  bypass the dispatch boundary, or claim capability by assumption, with
  no spec text to fail against.
analysis_ref: docs/plan/m1/tick-analysis/tool-transport-model-independence.md
blocked_by: []
files_scope:
  - docs/spec/llm.md
  - docs/spec/security.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY code, config, test, or design-note change — this ticket's diff is
    exactly the two spec files; the implementation is M1-872 (blocked on
    this landing) and the description data is M1-871.
  - >-
    ANY promise the detection cannot deliver: the wording must state the
    degrade-to-text rule and the transport-invariance of the tool
    boundary — never that a native tools shape is available, correct, or
    preferred (P16; capability is measurement-gated per the D79 posture
    and the cleared-set ships empty).
  - >-
    Dates, ticket IDs, measurement-record citations, or report references
    in the spec prose (engineering-rules §12; the history lives in the
    analysis document and the decision register) — and any edit to the
    closed tool table, the allowlist wording, or any other security.md
    promise: the security.md touch is ONE added sentence in §Prompt-
    injection defenses, nothing else.
acceptance:
  - "docs/spec/llm.md §SPI shape gains the transport rule as plain rule text: the chat agent's tool-call communication is transport-pluggable behind the fixed dispatch boundary — the instructed text protocol is the universal fallback any instruct model can learn; a structured tools request/response shape is used only where the serving endpoint's support is established by detection at resolution time, never assumed, and any unknown, unreadable, or rejecting endpoint serves the text protocol; the transport is resolved once per task and endpoint, never per call — probe: grep -n 'transport' docs/spec/llm.md returns the new §SPI shape sentences."
  - "docs/spec/security.md §Prompt-injection defenses gains one sentence pinning transport-invariance: the tool allowlist, argument validation, and per-turn caps apply identically whatever transport carried the call — probe: grep -n 'transport' docs/spec/security.md returns the new sentence beside the existing LLM-call-site commitments (:313-346)."
  - "The new spec prose states rules only — no dates, ticket IDs, or report citations — probe: grep -n 'M1-\|202[0-9]-' docs/spec/llm.md docs/spec/security.md shows NO new hit on any added line (§12)."
  - "The exact proposed text is shown to the user and approved before it lands (engineering-rules §12 — the ticket authorizes the amendment; the user approves the wording) — probe: the approval exchange is recorded verbatim in this ticket's body at implementation time, and grep -n 'user approved' docs/plan/m1/tick-tickets/M1-873-tool-transport-spec-amend.md returns that recorded entry; its absence fails the review gate."
  - "The spec-parsing suites that guard these files stay green after the amendment — ChatToolAllowlistSpecParityTest and DocumentedConfigKeyParityTest pass with the amended prose (no infochat.* token introduced, no closed-list perturbation) on a run that postdates the spec edit (green-log freshness) — probe: mvn -pl infochat-provider -am test -Dtest='ChatToolAllowlistSpecParityTest,DocumentedConfigKeyParityTest' is green."
  - "No other spec section changes — git diff --stat names exactly docs/spec/llm.md and docs/spec/security.md."
  - "mvn verify from repo root is green (the spec files are test-parsed; a doc-only skip does not apply here)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D32
  - D79
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for: docs/spec/llm.md §SPI shape
spec_amend_parent: M1-872
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-873: Record the tool-call transport architecture in spec

## Context

The analysis (analysis_ref:) verified that the spec is silent end-to-end
on how a tool call travels between the model and the dispatch boundary:
llm.md §SPI shape commits the SPI's purpose and routing but no transport
rule; security.md's tool commitments (allowlist, validation-before-SQL,
per-turn caps) are all dispatch-side. The architecture M1-872 implements
— text protocol as the permanent universal fallback, observed-dialect
bridges, a native tools wire shape only under detected support — is
correct today by construction, but nothing in spec protects it tomorrow:
a future diff could narrow the fallback to a legacy path, route a
structured call around the dispatcher, or assume capability from a model
name, with no spec text to fail against. This ticket records the
transport rule so the reviewer, the redteam, and future analysts have a
promise to hold diffs against. It is a RECORD in the M1-845/M1-846
amendment shape, not a SPEC-GAP: the spec nowhere forbids the layered
transport; this fills the silence deliberately, ahead of M1-872's diff.
Shared analysis: `analysis_ref:`.

## Root cause

Verified absence, not drift: `grep -n 'transport' docs/spec/llm.md`
returns no match; the spec's only tool-call-adjacent text is the
memory-retrieval recall tool, helpLookup, and trace ids. The detector-
ordering sentence in security.md :772-777 governs the strip's placement,
and the allowlist text governs the dispatch — neither states the
transport invariance that makes both safe under a pluggable transport.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P16 (and
honors P15's no-measurement posture — no campaign claim rides the
wording).

- P1: wrong-tier edit — inverted here: this ticket's ENTIRE diff is the
  spec edit, as a dedicated amendment (spec_amend_for / spec_amend_
  parent lineage); M1-872 is blocked on it so no implementation bends
  spec prose to fit.
- P16: spec-amendment discipline — rule text only (no dates, ticket
  IDs, or report citations); the user approves the exact wording even
  though this acceptance authorizes the amendment; the wording must not
  promise what detection cannot deliver (degrade-to-text and
  transport-invariant validation only — never availability or quality
  of a native shape).

## Approach

- **Files to touch:** `files_scope` — exactly docs/spec/llm.md §SPI
  shape and docs/spec/security.md §Prompt-injection defenses.
- **Steps, in implementation order:**
  1. Draft the two rule-text additions (the shapes in acceptance items
     1-2), keeping llm.md's existing SPI bullets and security.md's
     tool commitments untouched.
  2. Show the user the exact proposed text with a plain-English account
     of what commitment each sentence adds; land only on an explicit
     yes (§12).
  3. Run the parity suites post-edit (the freshness rule: the verify
     must postdate the edit).
- **Controls to preserve (§10):** the closed tool table, the allowlist
  wording, the detector-ordering sentence, and every other promise in
  both files — byte-untouched; the parity suites are the pin.
- **Pitfall→mitigation:** P1→dedicated-amendment lineage + item 6's
  diff probe; P16→items 3-4 (probe + approval exchange).

## Definition of done

Both rule-text additions landed under user approval; the no-citation
probe green; the parity suites green on a verify that postdates the
edit; the diff names exactly the two spec files; mvn verify green.

## Verification

- P1 → acceptance item 6 (git diff --stat probe) + the spec_amend_
  lineage fields (tick-lint resolves spec_amend_for against the §SPI
  shape anchor).
- P16 → items 3 (no-citation grep over the added lines) and 4 (the
  recorded approval exchange).
- P15 → no measurement claim rides the wording — item 3's no-citation
  probe (no 'M1-' or '202[0-9]-' hit on any added line) is also the
  no-campaign pin: the amendment records rules, never measurement
  outcomes; item 6's diff probe shows no other artifact rides the
  ticket.
- Parity carry → item 5 (ChatToolAllowlistSpecParityTest,
  DocumentedConfigKeyParityTest green post-edit).
- acceptance item 7 → mvn verify from repo root.

## Out-of-scope

Named in `out_of_scope`: any code/config/test/design change (M1-871/
M1-872 own those), any promise of native-shape availability or quality,
any citation in spec prose, and any edit beyond the one llm.md rule
block and the one security.md sentence.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-873-tool-transport-spec-amend.md
```
