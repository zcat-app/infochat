---
id: M1-789
title: "Strip scaffolding markers from LLM replies"
status: done
created: 2026-08-07
last_updated: 2026-08-07
flow: tick
reproduction: >-
  LlmOutputSanitizerTest.scaffoldingMarkersAreStrippedAndTheWrappedTextSurvives
  — the parked probe's scaffolding method (the markdown method stays
  parked under .scratch for M1-790) was restored, run RED at start
  (.scratch/m1-789-red-run.log: "WRONG BEHAVIOR ON MAIN: the wrapper
  opener reached the reader"), then folded into this permanent test and
  the probe deleted. Original evidence: the M1-779 repro log lines
  4284-4295 under .scratch.
analysis_ref: docs/plan/m1/tick-analysis/llm-output-leaks-scaffolding-markdown.md
blocked_by: []
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/llm/LlmOutputSanitizerCore.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE ADMIN-COMMAND CLOSED LIST (P11).
    `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList()` pins it
    against `docs/spec/commands.md`; not one entry is added, removed or
    reordered.
  - >-
    MARKDOWN. `**`, `---`, list markers pass through exactly as on main
    (P16) — the downgrade is M1-790, and no test or spec sentence here
    may pin its survival OR its removal.
  - >-
    THE `[REFUSAL:` / `TOOL_CALL:` DETECTORS (P5). They stay on raw
    text; M1-791 moves them. ACCEPTED RESIDUAL until then (review
    round 1): deleting a MID-LINE marker joins the flanking fragments,
    so the strip can assemble a contiguous `[REFUSAL:` / `TOOL_CALL:`
    token the raw-text detectors never saw — this ticket's earlier
    no-fragment-synthesis claim was wrong; it covered only the
    leading-line-drop route, which delivers bytes that were already contiguous and already delivered pre-ticket
    (handoff §5). M1-791's failure-mode tests must include the
    marker-join route.
  - >-
    PROMPT WORDING and the delimiter format itself. The per-call random
    marker stays exactly as it is; D21's guarantee rests on its
    unguessability, not on the shape being secret (M1-779
    out_of_scope, carried).
  - >-
    REWIRING `IngestTranslationWorker` (P13). It composes the core
    passes explicitly; stored-corpus markdown/scaffolding changes are
    a corpus change colliding with M1-776.
acceptance:
  - LlmOutputSanitizerTest.scaffoldingMarkersAreStrippedAndTheWrappedTextSurvives passes — REPRODUCTION. Feeds the probe's v1.1.0 reply (`<<<UNTRUSTED_CONTENT id="…">>>` … `<<<END id="…">>>` around real text) through the full `sanitize()` and asserts neither marker survives while the wrapped text does.
  - LlmOutputSanitizerTest.aMarkerOnlyLineIsDroppedNotBlanked passes — a marker-only line is DROPPED, not blanked (the M1-779 §Expected rendering; blanking is the rejected P6 shape).
  - LlmOutputSanitizerAuditRowIT.aCommandInsideAMarkerIdStillProducesARow passes — FAILURE-MODE (P3). `<<<END id="/grant-admin">>>` does NOT match the marker (the id class excludes `/`), falls through to the closed-list pass, and is redacted AND rowed.
  - LlmOutputSanitizerTest.scaffoldingStripWalksAManyLineReplyWithoutDecomposingIt passes — P1. The pass walks lines by index into one buffer (no `split("\n", -1)`, no per-line `ArrayList`/`StringBuilder`); the test walks a 200k-line reply within the existing 3s adversarial bound.
  - DisplayHeadlineTest.aNonRedactionCollapseOmitsTheHeadline passes — P2. `derive`'s `lines.length < 2` branch keeps its existing shape ONLY when the survivor is EXACTLY `REDACTED_COMMAND_REPLACEMENT`, otherwise returns the empty `AnchoredHeadline` (M1-714 omit shape); a feed title that flattens to a marker-shaped line cannot put the English anchor in the `originalLine` slot.
  - DisplayHeadlineTest.aForgedRedactionMarkerInTheAnchorCannotBuyBackTheCollapseLeak passes — FAILURE-MODE (P7). Equality, not contains; attacker prose appended to a forged `[redacted command]` does not buy the misattribution back.
  - The docs/spec/security.md §LLM output sanitizer amendment lands (scaffolding category, `/`-in-id rule, "not a D21 break" framing, collapsed-pair exact-equality rule) and the build stays green through mvn -B -pl infochat-provider -am verify, which runs ChatToolAllowlistSpecParityTest and DocumentedConfigKeyParityTest (P10 — no `infochat.*` token introduced).
  - mvn -B -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerAuditRowIT.java
  preserves:
    - >-
      LlmOutputSanitizerTest.matchSetEqualsSpecClosedList() — closed
      list untouched, spec parity holds.
    - >-
      THE FOUR STRADDLING-REDACTION TESTS, unchanged
      (DisplayHeadlineTest.anchorFirstRedactsAClosedListEntryStraddlingThePair,
      DisplayHeadlineTest.aSpanThatSwallowsTheSeparatorCollapsesToOneUnanchoredLine,
      DegradedDigestRendererTest.render_redactsAClosedListEntrySpanningTheAnchorAndTheOriginal,
      SummaryProseGeneratorTest.degradedProseRedactsAClosedListEntrySpanningTheAnchorAndTheOriginal)
      — they pin the M1-756/M1-759 visible-redaction control; exact
      equality (not "omit on any collapse") is what keeps them green.
    - >-
      LlmOutputSanitizerTest.adversarialFlagScanIsLinearNotQuadratic()
      — the P1 rewrite must not reintroduce a super-linear scan.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D21
  - D30
reviews:
  - round: 1
    date: 2026-08-07
    verdict: MANUAL
    checks: "SPEC-TRUTHNESS PASS, SECURITY FAIL, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "13 files changed, 332 insertions(+), 45 deletions(-)"
    findings: "1 medium (SECURITY) — mid-line marker deletion joins flanking fragments, so the strip CAN synthesize a contiguous [REFUSAL:/TOOL_CALL: token the raw-text detectors never saw; the ticket's 'no FRAGMENT SYNTHESIS' out_of_scope claim is false. Disposition is a user decision: re-sequence M1-791's detector move ahead of this ticket, or accept as a documented residual with corrected ticket + spec language."
    verdict_file: .scratch/tick-review-M1-789-r1.txt
  - round: 2
    date: 2026-08-07
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "4 files changed (fix hunks), 25 insertions(+), 9 deletions(-); full diff 14 files, 356 insertions(+), 52 deletions(-)"
    findings: "round-1 finding dispositioned SATISFIED (both EVALUATED-AS probes verified by the gate): residual stated in security.md:683, false claim removed from out_of_scope, M1-791 carries the marker-join route in Root cause + failure-mode Verification"
    verdict_file: .scratch/tick-review-M1-789-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  result: pass
  date: 2026-08-07
  notes: >-
    No blocking questions. Citations spot-checked against main
    (LlmOutputSanitizer.java:217-218 two-pass composition;
    DisplayHeadline.java:316-322 collapse branch; transform home in
    infochat-core). Superseded M1-779 redteam r1+r2 verdicts and the
    superseded diff read before code. blocked_by is empty, so no seam
    tests to trace. preserves claims falsified by reading: the
    straddling-pair collapse survivor is exactly
    REDACTED_COMMAND_REPLACEMENT (whole-pair consumption), so
    exact-equality keeps both DisplayHeadline straddling tests green;
    the other two straddling tests never reach derive; added tests pin
    no markdown behavior (P16).
---

# M1-789: Strip scaffolding markers from LLM replies

## Context

Model-echoed D21 wrapper markers (`<<<UNTRUSTED_CONTENT id="…">>>` …
`<<<END id="…">>>`) reach readers verbatim (v1.1.0 live test F4;
reproduced RED on main — see `reproduction:`). Internal scaffolding
renders as though it were content. This is NOT a D21 break: the id is
per-call random, so a leaked one helps forge nothing. Shared analysis:
`analysis_ref:` (pitfall numbering below matches it).

## Root cause

`sanitize()` composes exactly two passes — link strip, then closed-list
strip (`LlmOutputSanitizer.java:217-218`) — and nothing recognizes the
wrapper shape, so echoed markers pass through to delivery. The transform
home is `LlmOutputSanitizerCore` in infochat-core (M1-749); the bean is
delegate-only. A secondary, proven cause constrains the fix: a pass that
DELETES lines breaks `DisplayHeadline.derive`'s documented premise that
a one-line sanitize result means "the redaction swallowed the separator"
(DisplayHeadline.java:316-322) — the first line-dropper must land
together with that branch's fix, or a feed title shaped like a marker
line makes `derive` report the English anchor as the publisher's own
words (`anchored=false`, promoted by ClusterBlockRenderer, rendered
unbracketed — D29 (c) broken from feed bytes).

## Pitfalls

- P1: per-line decomposition DOS → index-walk into one buffer
  (security.md §Trust boundaries item 9 puts a hostile in-cap reply in
  this input).
- P2: dropped line reaches `derive`'s collapse branch → exact-equality
  fix ships in THIS ticket.
- P3: `id="[^"]*"` would let `/grant-admin` ride inside a marker id past
  the audit trail → id class excludes `/` (every real id is a UUID; r2
  re-verified every emitter).
- P6: blank-instead-of-drop is the rejected shape (yields
  `AnchoredHeadline(anchor, "", true)` → empty primary line; contradicts
  the §Expected rendering).
- P7: `contains` instead of exact equality hands the leak back — the
  literal is forgeable from a prompt-injected `title_en`.
- P10: security.md is parity-gated — no `infochat.*` token in the
  amendment.
- P11: closed list frozen.
- P12: only the private `derive` changes — `displaysAsTheOriginal` /
  `usesAnchor` semantics must not move (M1-778 gates on them).
- P13: do not rewire `IngestTranslationWorker`.
- P16: no test or spec sentence here may pin markdown behavior either
  way — M1-790 owns it.

## Approach

Derived from `spec_refs:` — §LLM output sanitizer's "full set of
LLM-authored output surfaces" plus §Prompt-injection defenses'
scaffolding-confidentiality posture, recorded back into the same
section as the M1-779 amendment precedent.

- **Files to touch:** the six in `files_scope`.
- **Steps, in order:**
  1. Restore the probe's scaffolding method, run RED (workflow §0).
  2. `LlmOutputSanitizerCore`: add `applyScaffoldingMarkerStrip` —
     line-oriented index walk into one StringBuilder (P1); a line
     carrying a marker keeps the non-marker residue; a line left blank
     is DROPPED (P6 anti-mitigation); id class `[^"/]*` (P3); id need
     not match or be present for the strip to fire.
  3. `LlmOutputSanitizer.sanitize()`: compose the new pass BEFORE the
     closed-list strip (the ordering rule: every DELETING pass runs
     before the redaction, so a joined token is re-scanned; P14's
     audit-preservation falls out of this placement). Static delegate
     in the bean, matching the existing shape.
  4. `DisplayHeadline.derive` `lines.length < 2` branch: keep the
     existing shape only when `truncated(sanitized)` equals
     `REDACTED_COMMAND_REPLACEMENT` exactly; otherwise return
     `AnchoredHeadline("", "", false)` (P2, P7). Nothing else in the
     class changes (P12).
  5. security.md amendment: scaffolding category, `/`-in-id rule, "not
     a D21 break" framing, collapsed-pair exact-equality rule (P10).
  6. Fold the probe assertion into `LlmOutputSanitizerTest`; delete the
     probe file.
- **Controls to preserve (§10):** closed-list strip + aggregated WARN +
  `LLM_OUTPUT_SANITIZED` rows (same path, same unit); pair-as-ONE-call
  sanitize unit; the four straddling tests unedited;
  `matchSetEqualsSpecClosedList`; the outbound `](` chokepoint
  (untouched, downstream).
- **Pitfall→mitigation:** P1→step 2 walk shape + walk test; P2→step 4;
  P3→step 2 id class + audit IT; P6→step 2 drops, never blanks;
  P7→step 4 exact equality + forged-marker test; P10→step 5 prose
  carries no config token; P11→no CLOSED_LIST edit; P12→step 4 touches
  only the branch; P13→out_of_scope; P16→test inputs carry no
  `**`/`---` assertions.

## Definition of done

Every `acceptance:` item green by its named test, including the two
failure-mode items; the amendment landed; full provider module verify
green with every preserved test unchanged.

## Verification

- reproduction → `LlmOutputSanitizerTest.scaffoldingMarkersAreStrippedAndTheWrappedTextSurvives`
- P1 → `LlmOutputSanitizerTest.scaffoldingStripWalksAManyLineReplyWithoutDecomposingIt` — 200k-line reply, 3s bound
- P2 → `DisplayHeadlineTest.aNonRedactionCollapseOmitsTheHeadline` — marker-shaped feed title collapses the pair; asserts the empty-headline omit shape, never the anchor in `originalLine`
- P3 → `LlmOutputSanitizerAuditRowIT.aCommandInsideAMarkerIdStillProducesARow` — feeds hostile `<<<END id="/grant-admin">>>`; asserts redacted rendering with exactly one row
- P6 → `LlmOutputSanitizerTest.aMarkerOnlyLineIsDroppedNotBlanked` — output contains no empty line where the marker stood
- P7 → `DisplayHeadlineTest.aForgedRedactionMarkerInTheAnchorCannotBuyBackTheCollapseLeak`
- P10 → `mvn -B -pl infochat-provider -am verify` runs ChatToolAllowlistSpecParityTest + DocumentedConfigKeyParityTest over the amended prose
- P11 → `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList` passes unedited; `git diff -- infochat-core` shows no CLOSED_LIST change
- P12 → `git diff -U0 -- infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java` shows hunks confined to the `derive` collapse branch; `grep` of the diff for `displaysAsTheOriginal`/`usesAnchor` returns nothing; DisplayHeadlineTest suite passes unchanged
- P13 → `git diff --name-only` piped through `grep IngestTranslationWorker` returns nothing
- P16 → `grep -n '\*\*\|---' infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java` over the ADDED tests asserts no markdown behavior pinned; the probe's markdown method stays unrestored and RED for M1-790
- preserved controls → the four straddling tests + `matchSetEqualsSpecClosedList` + `adversarialFlagScanIsLinearNotQuadratic`, all unedited

## Out-of-scope

Markdown (M1-790), the `[REFUSAL:`/`TOOL_CALL:` detector placement
(M1-791), the caller census (M1-792), the closed list, prompt wording,
the delimiter format, and `IngestTranslationWorker` wiring — each named
in `out_of_scope` with its reason. The `sanitize()`-returns-"" residual
(P8) is dispositioned by M1-792's census (delivery-path guard
follow-up), not here. No pre-existing test is modified by this ticket;
the four straddling tests are preserved byte-for-byte.
