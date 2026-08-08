---
id: M1-790
title: "Downgrade markdown for the plain-text surface"
status: done
created: 2026-08-07
last_updated: 2026-08-08
flow: tick
reproduction: >-
  parked: .scratch/M1779ReproProbeIT.java — restore ONLY the
  `markdownMustBeDowngradedToThePlainTextSurface` method (M1-789 owns
  the scaffolding method). Run RED at start, then fold the assertion
  into `LlmOutputSanitizerTest` as the permanent test and delete the
  probe. Evidence: .scratch/M1-779-repro-main.log:4297-4308 — "WRONG
  BEHAVIOR ON MAIN: markdown emphasis reached the reader".
analysis_ref: docs/plan/m1/tick-analysis/llm-output-leaks-scaffolding-markdown.md
blocked_by: [M1-789, M1-791]
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/llm/LlmOutputSanitizerCore.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE ADMIN-COMMAND CLOSED LIST (P11) — untouched, parity test
    unedited.
  - >-
    SCAFFOLDING MARKERS — M1-789 owns that pass; this ticket assumes it
    landed (blocked_by) and does not re-open its design.
  - >-
    MOVING THE `[REFUSAL:` / `TOOL_CALL:` DETECTORS — M1-791 owns that
    and is why this ticket is blocked on it (P5): the emphasis deletion
    SYNTHESIZES tokens (`[REFUS**AL**:` → `[REFUSAL:`, `TOOL_C**A**LL:`
    → `TOOL_CALL:`), and landing this pass while the detectors still
    read raw text re-opens the exact round-2 finding.
  - >-
    PROMPT WORDING and the delimiter format (carried from M1-779).
  - >-
    REWIRING `IngestTranslationWorker` (P13) — stored-corpus change,
    collides with M1-776.
  - >-
    THE PRE-EXISTING PREFIX-ONLY refusal-matching weakness (`---\n[REFUSAL:`
    shipped verbatim even before any deleting pass — handoff §5). Not
    this diff's regression; M1-792's census owns the follow-up.
acceptance:
  - LlmOutputSanitizerTest.markdownEmphasisIsDowngradedAndThematicBreaksAreDropped passes — REPRODUCTION. The probe's v1.1.0 reply through `sanitize()` carries no `**` and no `---` line, while the emphasized words survive.
  - LlmOutputSanitizerTest.d30AllowedSetSurvivesTheDowngrade passes — list markers downgrade to `· ` and D30's ALLOWED set is untouched BY THIS PASS: inline single-backtick spans, triple-backtick fenced blocks (delimiters AND contents), and bare URLs pass through.
  - LlmOutputSanitizerTest.bareUrlWithANonLowercaseSchemeIsProtectedToo passes — FAILURE-MODE (P4). The bare-URL guard is scheme-case-insensitive; `HTTPS://host/a*b*c` is never rewritten.
  - LlmOutputSanitizerTest.emphasisDeletionCannotAssembleACommandPastTheRedaction passes — FAILURE-MODE (P5, closed-list half). A deletion-join is re-scanned by the redaction it runs before: hostile `/b**a**n` must not survive as a command — it redacts to `[redacted command]` plus the audit row.
  - LlmOutputSanitizerTest.aThematicBreakWithACarriageReturnIsDroppedToo passes — P9. A thematic-break line is dropped even with a trailing `\r` (CRLF endpoint), keeping the spec sentence absolute.
  - LlmOutputSanitizerTest.plainTextDowngradeWalksAManyLineReplyWithoutDecomposingIt passes — P1. Index-walk, no per-line decomposition, under the existing 3s adversarial bound.
  - Pass ordering asserted by LlmOutputSanitizerTest.emphasisDeletionCannotAssembleACommandPastTheRedaction — the downgrade composes after the scaffolding strip and BEFORE the closed-list strip (the ordering rule); no deleting pass runs after the redaction.
  - The docs/spec/security.md §LLM output sanitizer amendment lands (markdown category: flanking-rule emphasis, thematic-break drop, `· ` list marker, fence scope stated "by this pass", scheme-case-folding rule, index-walk DOS rule, and the P14/P15 stated residuals) and the build stays green through mvn -B -pl infochat-provider -am verify, which runs ChatToolAllowlistSpecParityTest and DocumentedConfigKeyParityTest (P10 — no `infochat.*` token introduced).
  - mvn -B -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  preserves:
    - every test M1-789 added (the scaffolding strip is a sibling, not
      a casualty)
    - >-
      the four straddling-redaction tests and
      matchSetEqualsSpecClosedList(), unedited
    - >-
      LlmOutputSanitizerTest.adversarialFlagScanIsLinearNotQuadratic()
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D30
reviews:
  - round: 1
    date: 2026-08-08
    verdict: REWORK
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: FAIL
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: {files: 8, insertions: 508, deletions: 34}
    verdict_file: .scratch/tick-review-M1-790-r1.txt
  - round: 2
    date: 2026-08-08
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS-CHECK: PASS
      SECURITY-CHECK: PASS
      TEST-ADEQUACY-CHECK: PASS
      MAINTAINABILITY-CHECK: PASS
      SCOPE-CHECK: PASS
    diff_stats: {files: 9, insertions: 807, deletions: 119}
    verdict_file: .scratch/tick-review-M1-790-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-08-08
  notes: >-
    Citations spot-checked (sanitize() composition now at
    LlmOutputSanitizer.java:235-237, the ticket's :217-218 being the
    pre-M1-789 shape it names). All analysis pitfalls for this ticket
    (P1, P4, P5, P9, P10, P11, P13, P14, P15) landed; P16 verified
    honored by M1-789's fixtures (no markdown pinned). blocked_by test
    trace: M1-789's three tests and M1-791's synthesis-route tests are
    untouched by the downgrade. test_plan.preserves FALSIFIED in one
    documented place: LlmOutputSanitizerPostconditionTest#
    deletionShapesMatchTheirDocumentedPostconditions pins the pre-M1-790
    thematic-break-survives and emphasis-not-joined shapes at :283-292
    with comments mandating this diff flips them deliberately (census
    doc lines 32-33 say the same); those two assertions flip in this
    diff, outside files_scope per the pin's own mandate.
---

# M1-790: Downgrade markdown for the plain-text surface

## Context

Markdown (`**emphasis**`, `---` rules) survives into replies although
D30 (`docs/spec/decisions.md:47`) commits the messaging surface to plain
text (v1.1.0 live test F5; reproduced RED on main — `reproduction:`).
Prompting is demonstrably insufficient (the summarizer prompt already
states the reader cannot render markdown). This is the reactive half of
the M1-779 problem: every redteam round on the prior attempt ran
through the emphasis DELETION and the thematic-break DROP, which is why
this ticket is gated (see `blocked_by:`). Shared analysis: `analysis_ref:`.

## Root cause

No pass in `sanitize()`'s composition (LlmOutputSanitizer.java:217-218,
extended by M1-789 with the scaffolding strip) recognizes markdown the
D30 surface cannot render. The enforcement point, the pass ordering
(every DELETING pass before the closed-list strip), and the known
hazards (P1, P4, P5, P9, P14, P15) are all verified in the analysis.

## Pitfalls

- P1: per-line decomposition DOS → index-walk into one buffer.
- P4: case-sensitive URL guard rewrites `HTTPS://…` destinations →
  `(?i:https?)` verbatim span.
- P5: emphasis deletion SYNTHESIZES `[REFUSAL:` / `TOOL_CALL:` past
  raw-text detectors → hard dependency on M1-791; do not start without
  it. The closed-list half is covered by pass ordering (re-scanned).
- P9: CRLF endpoints make `\r` read as content → tolerate `\r` in the
  thematic-break and fence line matchers so the spec sentence stays
  absolute.
- P10: security.md parity — no `infochat.*` tokens.
- P11: closed list frozen.
- P13: do not rewire `IngestTranslationWorker`.
- P14: emphasis can SPLIT a token (`/ban*x*` → `/banx`), losing a row a
  pre-diff match produced — accepted residual (residue never
  dispatchable per the pattern's trailing lookahead; r2-corrected
  reasoning), STATED in the amendment.
- P15: on a closed-list match the NFKC canonical form is returned,
  possibly re-surfacing raw-invisible emphasis/markers — cosmetic
  residual, STATED; closing it would require a deleting pass after the
  redaction, which the ordering rule forbids.

Fixture calibration note (no pitfall tag — the trap belongs to the
earlier sibling): this is the LAST transform ticket, so its tests may
assert the final downgraded rendering outright; nothing pinned here is
mandated to change by any later sibling.

## Approach

Derived from `spec_refs:` — §LLM output sanitizer over D30's plain-text
commitment; the amendment records the category in the same section.

- **Files to touch:** the four in `files_scope`.
- **Steps, in order:**
  1. Restore the probe's markdown method, run RED.
  2. `LlmOutputSanitizerCore`: add `applyPlainTextDowngrade` —
     line-oriented index walk into one buffer (P1); emphasis removed per
     CommonMark's flanking rule (arithmetic prose and single `_` in
     identifiers survive); thematic-break line (`-*_` runs, 3+,
     spaces/tabs and an optional trailing `\r` only — P9) DROPPED; list
     marker → `· `; whole pass skipped inside backtick fences and for
     verbatim spans (`` `[^`]*` `` and `(?i:https?)://\S+` — P4).
  3. `sanitize()`: compose after the scaffolding strip, before the
     closed-list strip (ordering rule; P5 closed-list half, P14).
  4. security.md amendment per the acceptance item (P10, P14, P15
     stated).
  5. Fold the probe assertion into `LlmOutputSanitizerTest`; delete the
     probe file.
- **Controls to preserve (§10):** everything M1-789 preserved, plus
  M1-789's own new tests; the outbound `](` chokepoint stays downstream
  and untouched.
- **Pitfall→mitigation:** P1→step 2 + walk test; P4→step 2 span regex +
  failure-mode test; P5→`blocked_by: [M1-791]` + re-scan test; P9→step 2
  `\r?` matchers + CRLF test; P10→step 4 prose carries no config token;
  P11→no CLOSED_LIST edit; P13→out_of_scope; P14/P15→amendment states
  them.

## Definition of done

Every `acceptance:` item green by its named test, including the
failure-mode items; amendment landed; provider module verify green with
all preserved tests (including M1-789's) unchanged.

## Verification

- reproduction → `LlmOutputSanitizerTest.markdownEmphasisIsDowngradedAndThematicBreaksAreDropped` — feeds the v1.1.0 reply; asserts no `**` and no `---` survives
- P1 → `LlmOutputSanitizerTest.plainTextDowngradeWalksAManyLineReplyWithoutDecomposingIt`
- P4 → `LlmOutputSanitizerTest.bareUrlWithANonLowercaseSchemeIsProtectedToo` — failure-mode: feeds hostile `HTTPS://…/a*b*c`; asserts the destination is never rewritten
- P5 → `LlmOutputSanitizerTest.emphasisDeletionCannotAssembleACommandPastTheRedaction` — failure-mode: feeds `/b**a**n`; asserts the joined command does not survive — redacted plus one row; detector-side synthesis is impossible because M1-791 landed first (dependency, asserted by its own tests)
- P9 → `LlmOutputSanitizerTest.aThematicBreakWithACarriageReturnIsDroppedToo`
- D30 allowed set → `LlmOutputSanitizerTest.d30AllowedSetSurvivesTheDowngrade`
- P10 → `mvn -B -pl infochat-provider -am verify` runs ChatToolAllowlistSpecParityTest + DocumentedConfigKeyParityTest over the amended prose
- P11 → `LlmOutputSanitizerTest.matchSetEqualsSpecClosedList` passes unedited; `git diff -- infochat-core` shows no CLOSED_LIST change
- P13 → `git diff --name-only` piped through `grep IngestTranslationWorker` returns nothing
- P14 → `grep -n 'dispatchable' docs/spec/security.md` finds the stated residual in the amendment; `LlmOutputSanitizerTest.emphasisDeletionCannotAssembleACommandPastTheRedaction` asserts the join-side is re-scanned and rowed
- P15 → `grep -n 'canonical' docs/spec/security.md` finds the NFKC-return residual stated in the amendment

## Out-of-scope

The detector move (M1-791 — the reason for the block), the scaffolding
pass (M1-789), the closed list, prompts, the delimiter format,
`IngestTranslationWorker`, and the pre-existing prefix-only
refusal-matching weakness (M1-792's census follow-up). No pre-existing
test is modified; M1-789's tests are preserved byte-for-byte.

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-790-r1.txt):

1. FINDING 1: re-run applyScaffoldingMarkerStrip over the downgrade output
   before applyClosedListStripWithMatches in LlmOutputSanitizer.sanitize
   (LlmOutputSanitizer.java:246-249), so an emphasis-joined wrapper marker
   is stripped before delivery; evaluated via the new
   LlmOutputSanitizerTest case named in FINDING 1's EVALUATED-AS
   ("<<<UNTR*USTED*_CONTENT id=\"x\">>>" and "<<<E*N*D id=\"x\">>>"
   reduce to "", prose-carrying variant keeps prose and leaves no "<<<"),
   with the pre-existing scaffolding tests still green.
2. FINDING 2: replace the per-URL full-list overlaps() walk in
   verbatimSpans (LlmOutputSanitizerCore.java:746-785) with a linear merge
   of the two input-ordered span families (or a binary search over the
   backtick spans), semantics unchanged; evaluated via the new
   LlmOutputSanitizerTest case named in FINDING 2's EVALUATED-AS (≥2 MiB
   one-line reply with ≥100k code spans and ≥100k bare-URL chunks plus one
   paired emphasis completes under assertTimeoutPreemptively(3s) with the
   exact expected output), with the six existing downgrade tests still
   green.
   Both items: full mvn verify green afterwards.

## Review observations

RECOMMENDED-NEW-TICKET entries from round 1 (driver disposition: recorded,
filing is the user's call):

- Cross-line emphasis residue is not stated in the amendment (TOUCHED-BY-
  THIS-DIFF: yes). The per-line walk never pairs an opener on one line with
  a closer on a later line: sanitize("start **bold\ntext** end") returns
  the input unchanged — raw "**" reaches the reader although the surface
  cannot render it. Either the pass pairs across lines, or the amendment
  states this residue alongside the three it already states. Spec wording
  is the user's call. — RESOLVED in round 2: the user-approved amendment
  states it as the fourth residual.
- The scaffolding strip's own single-pass deletion can assemble a marker
  (pre-existing since M1-789, TOUCHED-BY-THIS-DIFF: no):
  sanitize("<<<E<<<END id=\"x\">>>ND id=\"y\">>>") delivers
  "<<<END id=\"y\">>>" — the strip removes the inner marker and joins
  "<<<E" with "ND ..." in the same pass. FINDING 1's re-run closes one
  nesting level while a deeper nesting still slips through; a general fix
  is a strip-until-fixpoint or a deletion shape that cannot join.
  (Carried DECIDE-BEFORE: M1-790 round 2 — relayed to the user.) —
  RESOLVED in round 2: the user chose "the deletion shape changes so it
  cannot join" — line-scope isolation closes the whole nesting class; no
  fixpoint, no depth walk.

## Round 2 rework (driver-directed)

Driver decisions (DECIDE-BEFORE resolved by the user):
- No formalizing of nested malicious input: no fixpoint loop, no depth
  walk. Round-1 rework item 1 (re-run the strip) SUPERSEDED — the
  deletion shape changes so it cannot join.
- M1-789's prose-keeping contract deliberately superseded: a
  marker-bearing line loses its prose; legit prose quoting a marker is
  accepted collateral (the reply's source link keeps the quoted post
  reachable).

Done:
- FINDING 1 + nesting: applyScaffoldingMarkerStrip is now line-scope
  isolation (a marker-bearing line drops wholesale; the
  sawContent/appendWithoutMarkers keep-prose machinery deleted; the
  /-in-id exclusion unchanged) with audit-on-drop (a dropped line is
  first matched on its canonical form via the existing closed-list
  machinery; matches join the call's aggregated WARN + audit rows).
  sanitize() composes link-flatten → downgrade → scaffold strip →
  closed-list strip (one strip call, post-downgrade, pre-redaction).
- FINDING 2: verbatimSpans is a linear merge of the two input-ordered
  span families (the per-URL full-list overlaps() walk deleted).
- Spec amendment landed per §12 (user-approved, one sharpened clause):
  scaffolding paragraph contract + rationale + audit-on-drop +
  /-exclusion restated; ordering paragraph pass list/example updated and
  strip-after-downgrade stated; M1-790 paragraph gains the
  strip-after clause and the fourth residual (cross-line emphasis).

Documented flips (same discipline as the round-1 postcondition pins):
- ChatAgentRefusalInterceptionTest#
  aRefusalMarkerJoinedByTheScaffoldingStripDegradesTheTurn RENAMED to
  aMarkerBearingRefusalLineIsDroppedBeforeItCanJoin and flipped: the
  marker-bearing line drops wholesale, the refusal marker is never
  assembled, the reply is "" — the join-then-detect shape is superseded
  (M1-791's post-sanitize detector control itself stands; the emphasis-
  route synthesis test still passes).
- ChatAgentRefusalInterceptionTest#aToolCallLineAssembledBySanitization-
  IsStripped: assertions unchanged (mechanism-agnostic), its
  marker-route comment updated to the drop-wholesale truth.
- New pin for the contract change: LlmOutputSanitizerTest#
  aMarkerBearingLineIsDroppedWholesaleNotExtractedAround.
