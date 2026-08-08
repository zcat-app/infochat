---
id: M1-791
title: "Run protocol-token detectors on sanitized LLM output"
status: done
created: 2026-08-07
last_updated: 2026-08-08
flow: tick
reproduction: >-
  ChatAgentRefusalInterceptionTest#aRefusalMarkerSurfacedOnlyBySanitizationDegradesTheTurn
  — ran RED on main (3/3 new tests: the zero-width + /ban reply delivered
  with the marker at index 0, the assembled TOOL_CALL line delivered and
  persisted, the scaffolding-joined marker delivered) via the
  canonical-form route: a reply of
  `<ZWSP>[REFUSAL: …] … /ban` does not trip the prefix check at
  ChatAgent.java:541 (the leading zero-width space), but `sanitize()`
  returns the CANONICAL form on the `/ban` match
  (LlmOutputSanitizerCore.java:536,561-567), so the persisted/delivered
  text leads with the protocol literal the detector exists to keep from
  readers. The test asserts the turn degrades (bundle string, null
  commit); on main it delivers.
analysis_ref: docs/plan/m1/tick-analysis/llm-output-leaks-scaffolding-markdown.md
blocked_by: [M1-778]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    TranslationPipeline's conditions (b)/(c) (TranslationPipeline.java:197-226)
    — same defect CLASS (raw-text checks upstream of sanitize) but a
    rendering/robustness failure shape, not protocol tokens; M1-778 is
    actively editing that method, and M1-792's census files the
    follow-up. Do not touch TranslationPipeline here.
  - >-
    WIDENING the refusal match beyond its current anchors
    (prefix-only at ChatAgent, two-sided at the generators). The
    pre-existing `---\n[REFUSAL:` weakness (handoff §5) is a matching
    weakness, not a placement one; M1-792's census owns that follow-up.
    This ticket changes the OPERAND the detectors evaluate, not what
    they match.
  - >-
    THE DELETING PASSES THEMSELVES (M1-789/M1-790) and the closed list
    (P11).
  - >-
    `displaysAsTheOriginal` / `usesAnchor` semantics (P12).
acceptance:
  - ChatAgentRefusalInterceptionTest.aRefusalMarkerSurfacedOnlyBySanitizationDegradesTheTurn passes — REPRODUCTION. The zero-width + closed-list-hit reply degrades exactly like the unavailable path (bundle string, null commit).
  - CategoryRollupGeneratorTest.aRefusalMarkerSurfacedBySanitizationYieldsNoRollup passes — the two-sided check at CategoryRollupGenerator evaluates the SANITIZED text, so a refusal marker surfaced only by sanitization yields `Optional.empty()`.
  - SummaryProseGeneratorTest.aRefusalMarkerSurfacedBySanitizationDegradesTheCluster passes — the two-sided check at SummaryProseGenerator evaluates the SANITIZED text, so a refusal marker surfaced only by sanitization degrades the cluster to degraded prose.
  - ChatAgentRefusalInterceptionTest.aToolCallLineAssembledBySanitizationIsStripped passes — FAILURE-MODE. `stripToolCalls` runs on the sanitized text, so a `TOOL_CALL:` line assembled BY sanitization never persists and never delivers.
  - Degrade-path consequences are byte-identical to today, verified by mvn -B -pl infochat-provider -am verify running the pre-existing plain-marker refusal tests at all three sites UNCHANGED — ChatAgent's bundle string + null commit, CategoryRollupGenerator's `Optional.empty()`, SummaryProseGenerator's degraded prose (a marker without a closed-list hit is returned byte-identical by sanitize(), LlmOutputSanitizerCore.java:561-562).
  - Audit behavior is preserved upward-only, verified by mvn -B -pl infochat-provider -am verify running the LlmOutputSanitizerAuditRowIT suite unchanged — sanitizing at generation in SummaryProseGenerator emits the `LLM_OUTPUT_SANITIZED` rows there, and the renderers' re-sanitize of `ClusterProse` bytes (ClusterBlockRenderer.java:183, DigestRenderer.java:610/:881) stays as the hand-assembled-record guard.
  - The docs/spec/security.md §Prompt-injection defenses amendment lands (the structured-refusal and tool-call detectors evaluate the sanitized output — detector ordering is downstream of every deleting pass) and the build stays green through mvn -B -pl infochat-provider -am verify (P10 — no `infochat.*` token introduced).
  - mvn -B -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptionTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  preserves:
    - the existing plain-marker refusal tests at all three sites, unedited
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D21
reviews:
  - round: 1
    date: 2026-08-08
    verdict: APPROVE
    checks:
      spec_truthness: PASS
      security: PASS
      test_adequacy: PASS
      maintainability: PASS
      scope: PASS
    diff_stats: "9 files changed, 370 insertions(+), 65 deletions(-)"
    renames_material: "trimmedFinalText (ChatAgent) now trims approved, not finalText — candidate for the Renames: trailer"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  lint: "tick-lint: 0 finding(s), 0 BLOCKER(s)"
  self_check: >-
    Citations spot-checked against post-M1-789 main: line numbers drifted
    (prefix check ChatAgent.java:563, stripToolCalls :542/:1079, rollup
    check CategoryRollupGenerator.java:212 with sanitize at :221, summary
    check SummaryProseGenerator.java:144, canonical-form return
    LlmOutputSanitizerCore.java:646-652) but every code claim holds.
    Census grep re-runs clean; every returned path has a row. Analysis
    pitfalls P5/P10/P12/P16 all landed. M1-778 seam tests traced under the
    reorder: ChatAgentReplyLanguageTest (pass-through agent sanitizer),
    SummaryProseLanguageTest + CategoryRollupGeneratorTest language arms
    (prompt-side assertions) — unaffected. test_plan.preserves falsified
    now: all plain-marker tests (ChatAgentRefusalInterceptTest x7,
    refusalMarkerYieldsCategoryWithoutPrefix,
    SummaryProseRefusalDegradeTest) run identity or sanitize-invariant
    operands, stay green unedited; SummaryProseRefusalDegradeTest:70
    verbatim-prose pin holds because plain prose is sanitize-invariant.
    Coordination note: M1-792 is in-flight in the main checkout on the
    same module (user-directed); this branch forks from main, which lacks
    M1-792's staged postcondition test — merge order resolves via the
    flow's rebase arm. No open ambiguity.
---

# M1-791: Run protocol-token detectors on sanitized LLM output

## Context

The unresolved redteam round-2 finding on M1-779 (INFO-LEAK/medium):
character-joining transforms inside `sanitize()` can assemble the D21
refusal marker (`[REFUSAL:`) or a `TOOL_CALL:` line out of fragments the
upstream detectors never saw, because all four detectors read the RAW
model reply and sanitize afterwards. ChatAgent's own soundness comment
(:534-536 — "since strip only deletes text, a post-strip prefix match
cannot arise") holds only for deleting passes placed BEFORE the check.
A narrow instance exists on main TODAY (see `reproduction:`), so this is
not conditional on M1-790: it is a live defect the markdown downgrade
would widen from rare to routine. Shared analysis: `analysis_ref:`
(P5). Blocked on M1-778, which owns ChatAgent.java
(`docs/plan/m1/tickets/M1-778-generated-prose-ignores-scope-language.md`
files_scope).

## Root cause

Verified: `ChatAgent.java:541` (prefix check) and `:520`/`:1057`
(`stripToolCalls`), `CategoryRollupGenerator.java:203`,
`SummaryProseGenerator.java:128` all evaluate pre-sanitize text, while
`applyClosedListStripWithMatches` returns the CANONICAL form on any
match (LlmOutputSanitizerCore.java:536,561-567) — a representation
change (bidi/zero-width strip, NFKC) the raw-text detectors never ran
on. Deleting passes added by M1-789/M1-790 widen the same channel
(line drops move tokens to index 0; emphasis deletion joins fragments).
M1-789's scaffolding strip adds a third route on landing: deleting a
MID-LINE marker joins the flanking fragments (`TOOL_C<<<END>>>ALL:` →
`TOOL_CALL:`) with no closed-list hit required (M1-789 review round 1).

## Pitfalls

- P5: the mechanism itself — the fix must put every protocol-token
  detector downstream of every deleting pass, not add another upstream
  patch.
- P10: security.md parity — no `infochat.*` tokens.
- P12: M1-778 owns ChatAgent.java — hence `blocked_by`; do not start
  early against a moving file.

Untagged traps (no analysis number — local to this ticket's edit):
ordering — `sanitize()` must still run BEFORE persist (ChatAgent.java:547
comment — "admin commands never enter the DB"); the reorder keeps persist
and delivery consuming exactly the text the detectors approved.
Double-audit — a refusal check that calls `sanitize()` purely to inspect,
then lets render sanitize again, would emit `LLM_OUTPUT_SANITIZED` rows
TWICE for one delivery on a closed-list hit; SummaryProseGenerator
therefore sanitizes ONCE and carries the sanitized bytes in
`ClusterProse` (the renderer re-sanitize is then a no-op: no matches →
no rows). Degrade-path drift — the trigger's operand moves, never the
consequence.

## Approach

Derived from `spec_refs:` — §Prompt-injection defenses' refusal
contract ("never surface the marker") read against §LLM output
sanitizer's transform pipeline: a detector guards delivered bytes, so
it must evaluate delivered bytes.

- **Files to touch:** the six in `files_scope`.
- **Steps, in order:**
  1. Write the reproduction test at ChatAgent, run RED.
  2. ChatAgent: reorder so `stripToolCalls` and the `[REFUSAL:` check
     evaluate the sanitized text; persist and deliver exactly the
     approved text. Keep the check prefix-only (out_of_scope names
     why).
  3. CategoryRollupGenerator: evaluate the two-sided check on the
     `sanitize()` result (sanitize already runs at :212 — swap the
     order).
  4. SummaryProseGenerator: sanitize once at generation, run the
     two-sided check on the sanitized text, carry the sanitized bytes
     in `ClusterProse`. If any existing test pins RAW prose carriage,
     update it HERE with the new expectation (see Out-of-scope
     authorization).
  5. security.md: record the detector-ordering sentence (P10).
- **Controls to preserve (§10):** the degrade paths (unchanged); the
  sanitize-before-persist invariant; the render-side re-sanitize of
  `ClusterProse`; per-token aggregated WARN + audit rows on every
  sanitize call that matches.
- **Pitfall→mitigation:** P5→steps 2-4; double-audit→step 4 sanitize
  once + renderer no-op property (pinned by the preserved plain-marker
  tests and the LlmOutputSanitizerAuditRowIT suite);
  ordering→step 2 keeps sanitize before persist; P10→step 5 prose
  carries no config token; P12→`blocked_by`.

## Definition of done

Every `acceptance:` item green by its named test, including the
TOOL_CALL failure-mode item; the three sites' plain-marker refusal
tests pass unedited; provider module verify green.

## Verification

- reproduction → `ChatAgentRefusalInterceptionTest.aRefusalMarkerSurfacedOnlyBySanitizationDegradesTheTurn` — zero-width + `/ban` reply; asserts bundle reply + null commit
- P5 (rollup) → `CategoryRollupGeneratorTest.aRefusalMarkerSurfacedBySanitizationYieldsNoRollup` — same synthesis route; asserts `Optional.empty()`
- P5 (summary) → `SummaryProseGeneratorTest.aRefusalMarkerSurfacedBySanitizationDegradesTheCluster` — asserts degraded prose for the cluster
- P5 (tool-call, failure-mode) → `ChatAgentRefusalInterceptionTest.aToolCallLineAssembledBySanitizationIsStripped` — a `TOOL_CALL:` line present only in the canonical/sanitized form never persists and never reaches the reader; the test feeds BOTH assembly routes: the canonical-form route and M1-789's marker-join route (`TOOL_C<<<END id="x">>>ALL: {…}`, and `[REFUS<<<END id="x">>>AL: …` for the refusal arm)
- P10 → `mvn -B -pl infochat-provider -am verify` runs ChatToolAllowlistSpecParityTest + DocumentedConfigKeyParityTest over the amended prose
- P12 → `python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-791-*.md` resolves `blocked_by: [M1-778]`, and the flow's start gate refuses while M1-778 is not done
- degrade-path preservation → existing plain-marker refusal tests at all three sites, unedited and green in `mvn -B -pl infochat-provider -am verify`
- double-audit trap → LlmOutputSanitizerAuditRowIT suite passes unchanged; row counts stay 1-per-distinct-token per delivery

## Out-of-scope

TranslationPipeline (b)/(c) and the prefix-only matching widening —
both are census-owned follow-ups (M1-792), named in `out_of_scope`.
The deleting passes and the closed list belong to M1-789/M1-790.

**Authorized pre-existing test modification:** if a
`SummaryProseGeneratorTest` fixture pins that `ClusterProse.prose()`
carries the RAW model text, it is updated to pin the SANITIZED text,
because this ticket deliberately moves sanitization to generation; the
property the renderer-side tests pin (renderers re-sanitize whatever a
record carries) is unchanged. Any other pre-existing test edit is
unauthorized.

## Census

Class: protocol-token detectors evaluating raw LLM output upstream of
`sanitize()`. Re-runnable enumeration:
`grep -rn '\[REFUSAL:\|TOOL_CALL' infochat-provider/src/main/java infochat-collector/src/main/java`.

| Site | Disposition |
|---|---|
| ChatAgent.java:541 (`startsWith("[REFUSAL:")`) | FIXED here — post-sanitize |
| ChatAgent.java:520 + :1057 (`stripToolCalls`) | FIXED here — post-sanitize |
| CategoryRollupGenerator.java:203 (two-sided) | FIXED here — post-sanitize |
| SummaryProseGenerator.java:128 (two-sided) | FIXED here — post-sanitize |
| ChatPromptBuilder.java:61 | out-of-scope — the prompt literal instructing the model, not a detector |
| Stage1RegexSet.java:85/:149 | out-of-scope — collector ingest regex catalogue, a different surface with its own boundary (§Ingest pipeline) |
| TranslationPipeline.java:197-226 (conditions b/c) | defer: M1-792 census follow-up — same class, different failure shape, M1-778-owned method |
