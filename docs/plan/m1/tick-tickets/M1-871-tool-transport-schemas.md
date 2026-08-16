---
id: M1-871
title: "Single-source tool catalog for prompts and transports"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  ChatToolCatalogTest#catalogMatchesRegistryNamesAndInstructionLines
  (to-be-written: child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/tool-transport-model-independence.md —
  /tick start converts the marker by writing the test and running it
  RED before any fix code, workflow §0; it runs red as the M1-847
  compile-failure shape — the catalog type does not exist). Probe of
  today's wrong posture: nothing
  mechanically forces the tool descriptions to have ONE source — the
  instruction text is a hand-maintained string (ChatAgent.java:78-106)
  guarded by four hand-written param tests covering 4 of the 7 tools
  (ChatAgentTest.java:919-960) plus a name-only walk
  (everyRegisteredToolIsAdvertised, :972-982). The M1-070 drift class
  (instructions naming parameters the tools ignore made the surface
  non-functional) is still open: a future arg rename that updates the
  tool but not the string passes every pin today except by accident, and
  a future tools-bearing wire shape (M1-872) would have NO source to
  render its declarations from.
analysis_ref: docs/plan/m1/tick-analysis/tool-transport-model-independence.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolCatalog.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The tool allowlist in ANY form: ChatToolRegistry.TOOL_NAMES, the
    dispatcher map, and the security.md §Prompt-injection defenses table.
    The catalog DESCRIBES the same seven tools; it adds, renames, or
    re-scopes nothing (P1 — the allowlist is closed at spec level; a
    catalog entry without a registry twin fails the parity test).
  - >-
    ChatToolDispatcher, the tool implementations, and their argument
    parsing — the catalog is DESCRIPTION, not validation; every runtime
    boundary stays exactly as is.
  - >-
    Any wire transport — the JSON-Schema-shaped parameters rendering this
    ticket adds is DATA (consumed by M1-872); no provider, SPI, or request
    assembly changes here.
  - >-
    The worked example line, the tool-plane English sentence, and every
    non-tool-table region of TOOL_INSTRUCTIONS (ChatAgent.java:79-106) —
    only the per-tool lines are rendered from the catalog, byte-identically
    (P2's stop-and-escalate rule).
  - >-
    Any docs/spec/** edit and any model-facing behavior change: the
    rendered instruction block is pinned byte-identical to today's, so no
    measured surface moves (P12 — no re-measurement owed).
acceptance:
  - "ChatToolCatalogTest.catalogMatchesRegistryNamesExactly passes — the catalog's name set equals ChatToolRegistry.toolNames() byte-for-byte in BOTH directions (no entry without a registry twin, no registry tool without an entry), mirroring the security.md-table parity posture (verification.md §Security)."
  - "ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing passes — FAILURE-MODE (P7): for each of the SEVEN tools, the declared parameter names and types match what the tool actually reads (searchPosts tags/window/limit, semanticSearch query/limit, getPost uid, getReferences uid/limit, recallMemory keywords, listSaves tags/window, helpLookup query) — a declaration the tool ignores fails here loudly, closing the M1-070 class mechanically."
  - "ChatAgentTest.renderedInstructionTableIsByteIdentical passes — the TOOL_INSTRUCTIONS tool table rendered from the catalog equals today's hand-written lines byte-for-byte; the four toolInstructionsMatch*Params tests, everyRegisteredToolIsAdvertised, workedExampleLineParsesWithTheShippedMatcher, and finalCallOmitsToolInstructions pass UNCHANGED (§8: NO pre-existing test modification is authorized — if byte-identity is unreachable for a line, STOP and escalate; do not edit the pins)."
  - "ChatToolCatalogTest.parametersRenderAsValidJsonSchema passes — each tool renders a JSON-Schema-shaped parameters object (object type, per-param type keywords) that parses as valid JSON — the shape a tools-bearing wire request consumes (M1-872's input; verified data here, no wire change)."
  - "A drift probe: renaming or editing any catalog arg declaration without the matching tool/instruction change fails ChatToolCatalogTest — the parity tests are the mechanical guard, not review diligence."
  - "The ChatToolRegistryTest and ChatToolDispatcherTest suites pass UNCHANGED — the registry and dispatch boundaries are untouched (§10) — probe: mvn -pl infochat-provider -am test -Dtest='ChatToolRegistryTest,ChatToolDispatcherTest' is green."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 documents the catalog as the single source for tool descriptions (instruction table and future transport declarations) — probe: grep -n 'catalog' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mention."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatToolCatalogTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (the byte-identity pin)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D21
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

# M1-871: Single-source tool catalog for prompts and transports

## Context

The chat tool surface describes its seven tools in ONE place only: the
hand-maintained `TOOL_INSTRUCTIONS` string (ChatAgent.java:78-106). Its
guards are four hand-written param tests (4 of 7 tools) plus a name-only
advertising walk — exactly the shape inside which M1-070 shipped
instructions naming `{query}`/`{limit}` for tools that read
`{tags, window, limit}`/`{keywords}`, making the whole filtered-query
surface non-functional. The transport decomposition (analysis
`analysis_ref:`) needs tool declarations as DATA anyway — an
OpenAI-style `tools` array (M1-872) renders from JSON-Schema-shaped
parameters — so the fix and the prerequisite are the same object: a
single-source catalog. Shared analysis: `analysis_ref:`.

## Root cause

Verified: no single source exists. The instruction string is literals;
the parity pins are partial (ChatAgentTest.java:919-982); the M1-664 walk
checks NAMES only, not arg shapes; and nothing links either to a
machine-readable declaration a wire transport could render. The drift
class (instruction/tool mismatch) is therefore guarded by review
diligence alone — the same posture that failed once already (M1-070) and
that M1-425's collector-side regex drift shows recurs wherever
descriptions and parsers are maintained apart.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P7, P12,
P13.

- P1: wrong-tier edit — the catalog is description-tier data;
  docs/spec/** untouched; the allowlist is NOT widened (parity both
  directions).
- P2: the pre-existing instruction pins are the guard — the rendering
  must be byte-identical or the ticket escalates; editing a pin to fit
  a renderer is the M1-785 self-break class (§8 authorization posture).
- P7: schema drift — every declared arg name/type must match the tool's
  actual parsing, pinned for ALL SEVEN tools (M1-070); derivations and
  matchers must not over-match (M1-425).
- P12: fixture calibration — this ticket pins catalog↔registry↔
  instruction parity with byte-identical rendering; nothing here
  constrains M1-870's strip tests or M1-872's wire tests.
- P13: sequencing — M1-870 lands first (same file, disjoint regions);
  never `--parallel` in the same module; M1-872 renders from this
  catalog and lands after.

## Approach

- **Files to touch:** `files_scope` — a new package-scoped
  `ChatToolCatalog` (per tool: name, one-line purpose, ordered arg
  declarations with type/required shape); ChatAgent.java (the
  TOOL_INSTRUCTIONS tool table becomes a rendering of the catalog —
  every other region of the constant byte-untouched);
  ChatToolCatalogTest.java (new); ChatAgentTest.java (the byte-identity
  pin only); design 05 §5.4.6.
- **Steps, in implementation order:**
  1. Write ChatToolCatalogTest RED (compile-failure shape — the type is
     absent; the M1-847 precedent).
  2. Add the catalog with the seven tools' declarations transcribed
     EXACTLY from the current instruction lines and the tools' actual
     parsing; add the JSON-Schema parameters rendering beside it.
  3. Render the instruction tool table from the catalog and pin
     byte-identity (a new ChatAgentTest case asserting the rendered
     table equals today's literal lines); the four param tests and the
     walks stay green UNCHANGED.
  4. Update design 05 §5.4.6 (catalog = single source for tool
     descriptions; the schema rendering is the transport-side face).
- **Controls to preserve (§10):** the registry allowlist and dispatcher
  boundary untouched (their suites green unchanged); the instruction
  bytes the model sees are pinned byte-identical — no measured surface
  moves, no re-measurement owed; the worked example and tool-plane
  sentence untouched (M1-856/M1-857 pins).
- **Pitfall→mitigation:** P1→parity both directions (item 1); P2→item 3's
  byte-identity pin + the stop-and-escalate rule; P7→item 2's
  transcription source + item 2's all-seven pin; P12→test_plan scope;
  P13→ordering note (allocation: after M1-870).

## Definition of done

The catalog exists with byte-parity to the registry in both directions,
all-seven arg-shape pins green, the rendered instruction table
byte-identical (every pre-existing instruction test green UNCHANGED),
the JSON-Schema rendering valid, design 05 §5.4.6 naming the single
source, and mvn verify green from the repo root.

## Verification

- P1 → git diff --stat names exactly the files_scope paths — no
  docs/spec/** path, no registry/dispatcher/tool-implementation path.
- P2 → ChatAgentTest.renderedInstructionTableIsByteIdentical + the
  unchanged pre-existing pins (acceptance item 3).
- P7 → ChatToolCatalogTest.everyCatalogArgsShapeMatchesToolParsing —
  feeds nothing; it DECLARES the contract and fails on any
  instruction/tool/catalog disagreement (non-vacuous: an arg rename in
  any one of the three places fails it — the M1-651 mutation standard).
- P12 → test_plan.adds names only parity/byte-identity pins.
- P13 → the ordering note in this ticket + the analysis decomposition.
- FAILURE-MODE coverage → item 2 (a catalog declaration the tool
  ignores — the M1-070 drifted-declaration hostile input — fails
  loudly) and item 3's byte-identity pin (a renderer that silently
  reflows a pinned instruction line fails it): both feed the ticket's
  own production object a wrong entry and assert the guard rejects it.
- acceptance item 5 → the drift probe is items 1-3 themselves (any
  unilateral edit fails one of them).
- acceptance item 7 → the named grep probe; item 8 → mvn verify.

## Out-of-scope

Named in `out_of_scope`: the allowlist in any form, the dispatcher and
tool implementations, any wire transport (M1-872), the non-table regions
of TOOL_INSTRUCTIONS, any spec edit, any model-facing behavior change.
No pre-existing test is modified (§8) — the byte-identity requirement is
deliberate; if a line cannot be rendered byte-identically the ticket
escalates rather than editing a pin.

## Census

The guarded class is "every tool description site". Enumeration:
`grep -rn 'searchPosts\|semanticSearch\|recallMemory\|listSaves\|helpLookup' infochat-provider/src/main/java`
(hand-curated prose hits aside, the load-bearing sites):

| Site | Disposition |
|---|---|
| ChatAgent.TOOL_INSTRUCTIONS tool table | FIXED here — rendered from the catalog |
| ChatToolRegistry.TOOL_NAMES | guarded — byte-parity test added (item 1) |
| security.md §Prompt-injection defenses table | out-of-scope — spec surface, unchanged (spec-level parity already CI-gated per verification.md §Security) |
| ChatToolDispatcher map / tool impls | out-of-scope — runtime boundary, unchanged |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-871-tool-transport-schemas.md
```
