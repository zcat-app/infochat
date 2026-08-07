---
id: M1-787
title: "Straddled second-pass match must keep its row and redaction"
status: done
created: 2026-08-07
last_updated: 2026-08-07
flow: tick
reviews:
  - round: 1
    date: 2026-08-07
    verdict: APPROVE-WITH-FIXES
    checks: SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN, SCOPE PASS
    diff_stats: 4 files, +182/-28
    note: >-
      One low comment-only finding: the rewritten
      splitMatchesAroundPlaceholders javadoc was a single ~430-char line
      in a file that wraps javadoc at ~75 cols. Fix applied in-band
      (rewrap, zero executable lines). Probe passes — sed -n '470,485p'
      Stage1Pipeline.java | awk 'length($0) > 90' returns no output;
      ./mvnw -B -pl infochat-collector -am test-compile BUILD SUCCESS
      (.scratch/tick-M1-787-testcompile.log). Side effect accepted per
      user decision 2026-08-07: the rewrap trips tick-comment-cap (7
      consecutive added comment lines at Stage1Pipeline.java:477) — the
      cap (line count) and the gate's FIX ITEM (75-col wrap, probe
      rejects >90-char lines) cannot both hold for this content; the
      gate's verdict is authoritative and its own MAINTAINABILITY check
      ordered this form. Round-1 green log target/tick-test-M1-787-r1.log
      remains the log of record. No RECOMMENDED-NEW-TICKET entries.
clarity_check: "start 2026-08-07: ticket claims verified against the tree; two
              stale-after-M1-788 citations, substance intact — (1) line numbers
              drifted ~+15: the filter is Stage1Pipeline.java:482-510 (call site
              :447-449), 'dropped whole' javadoc :93-96, method javadoc :475-481,
              first-pass replacement loop :413-422, second-pass loop :451-460,
              flagged :462; (2) Stage1BodyTextIT is now 16 tests (M1-788 added 4),
              not 12 — the acceptance intent 'passes in full unchanged' applies,
              and none of the 4 new bodies produces a first-pass marker plus a
              straddling second-pass match, so the non-interference claim holds.
              M1-786 already carries blocked_by [M1-784, M1-787, M1-788]; no
              driver edit needed."
reproduction: Stage1PipelineIT#straddlingPayloadIsStillQuarantinedAndRedacted (@Order(19),
              written at `start` and run RED on 2026-08-07 before any fix code:
              expected 3 rows, was 1 — .scratch/tick-M1-787-red.log).
              The wrong behavior was already executable pre-fix via the GREEN
              pin test
              Stage1PipelineIT.secondPassRedactionNeverOverwritesAFirstPassPlaceholder
              (@Order(16), Stage1PipelineIT.java:492-512, green in
              .scratch/tick-test-M1-785-r1.log): body
              "&amp;#105;gnore\nsystem: previous instructions" stored
              "ignore\n[REDACTED:<id>] previous instructions" with ONE
              quarantine row (stage1.impersonation_prefix) and NONE for
              stage1.ignore_previous_instructions — the decoded payload text
              stays literal in post.body and its rule never reaches
              /quarantine list. Control: drop the "system:\n" line and the
              same payload is caught and redacted
              (Stage1PipelineIT.doublyEncodedEntityInjectionIsDetectedNotBypassed,
              @Order(15)). Expected after the fix: 2 rows with rule_id
              stage1.ignore_previous_instructions (one per non-marker segment,
              original_html "ignore\n" and " previous instructions"), the
              impersonation row unchanged, no literal payload text in the
              column, every placeholder verbatim.
analysis_ref: self
blocked_by: [M1-784]
remediates: M1-785
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the pre-existing restore no-op for a first-pass marker deleted by the
    sanitizer together with its enclosing element (M1-785 §Review
    observations, entry 2) — untouched by the second-pass filter this ticket
    reworks
  - editing docs/plan/m1/tick-tickets/M1-786-*.md — this ticket RECOMMENDS
    M1-786 gain blocked_by [M1-787] (see Context); applying that is the
    driver's edit, not this diff's
  - Stage1RegexSet — the pattern set is a closed spec-level commitment
  - charging segmentation to the ScanBudget, adding any new budget, config
    key, or scan pass — the shared per-input deadline and match allowance
    stay exactly as M1-785 built them
  - the stored representation / plain-text sink (M1-784's, already landed)
    and the OWASP policy
  - approve_quarantine (V69) and any migration — the restore procedure is
    the invariant this ticket preserves, not a file it edits
  - infochat-provider/**
acceptance:
  - Stage1PipelineIT.straddlingPayloadIsStillQuarantinedAndRedacted passes —
    the reproduction; body "&amp;#105;gnore\nsystem: previous instructions"
    yields 2 quarantine rows with rule_id stage1.ignore_previous_instructions
    (original_html byte-exact "ignore\n" and " previous instructions") plus
    the impersonation row, a stored post.body containing no literal "ignore"
    and no "previous instructions", every quarantine placeholder_id for the
    post verbatim in post.body, status RAW, stage1_flagged TRUE, and
    Stage1Result.redactedBody() equal to the SELECTed column
    (docs/spec/security.md §Ingest pipeline, §Quarantine workflow)
  - Stage1PipelineIT.payloadStraddlingTwoPlaceholdersIsFullyRedacted passes
    (P1, P3, P7) — failure mode: a hostile body whose one second-pass match
    straddles TWO first-pass markers (both .{0,40} windows of rule 1 filled
    by markers); asserts 3 segment rows + 2 impersonation rows, no literal
    payload word survives in post.body, all 5 placeholder ids verbatim, and
    every row's original_html non-empty
  - Stage1PipelineIT.fakePlaceholderShapedTextCannotShieldAPayload passes
    (P2) — failure mode: a body embedding a literal
    [REDACTED:AAAAAAAAAAAAAAAAAAAAAAAAAA] inside the payload plus a real
    first-pass trigger on another line; asserts the fake marker text does NOT
    survive in post.body, the payload's rule row exists, and the real
    first-pass placeholder survives verbatim — protected spans are keyed by
    emitted ids, never by bracket shape
  - Stage1PipelineIT @Order(15)-(18) — each passes unchanged (P1, P5): in
    particular @Order(16)
    secondPassRedactionNeverOverwritesAFirstPassPlaceholder (the
    marker-integrity control this rework must keep green) and @Order(17)
    stage1ResultRedactedBodyEqualsTheStoredColumn
  - Stage1SharedScanBudgetIT.secondScanSharesTheSinglePerInputWatchdogAndMatchBudget passes
    unchanged, and
    Stage1MatchOverflowIT.matchOverflowSealsPostAtQuarantinedAndSkipsRedactPath
    passes unchanged (P4) — segmentation adds no scan, charges no budget, and
    the fail-closed overflow path is byte-identical
    (docs/spec/security.md §Ingest pipeline, §Failure handling)
  - Stage1BodyTextIT passes in full (12 of 12) unchanged — M1-784's
    plain-text pins, including redactionPlaceholderSurvivesByteExact's
    exactly-one-placeholder assertion on its non-straddling body
  - grep -n "dropped whole" on Stage1Pipeline.java returns nothing, and the
    class javadoc second-scan bullet describes per-segment redaction (P6)
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java
      (three new @Test methods appended at @Order(19)-(21); no existing
      method's body or assertions are modified)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Ingest pipeline
  - docs/spec/security.md §Quarantine workflow
  - docs/spec/security.md §Failure handling
  - docs/design/04-security.md §4.2 Layered ingest security
decision_refs:
  - D20
---

# M1-787: Straddled second-pass match must keep its row and redaction

## Context

M1-785's second scan redacts and quarantines what the first scan could not
see — except when an attacker asks it not to. `withoutMatchesTouchingAPlaceholder`
(`Stage1Pipeline.java:467`–`495`) drops a second-pass match WHOLE when its
span overlaps a first-pass `[REDACTED:<id>]` marker: the payload text around
the marker stays literal in `post.body` and no quarantine row records that
the payload's rule fired. The overlap is attacker-selectable: rules 1/2/4
carry `.{0,40}` DOTALL interstitials (`Stage1RegexSet.java:99`–`132`) and the
marker is 37 bytes, so a feed operator plants a cheap first-pass trigger
(e.g. a `system:` line, rule 3) inside the payload's window and the payload's
own match straddles the marker deterministically. Demonstrated by the GREEN
pin `Stage1PipelineIT` @Order(16) (`:492`–`512`) versus the @Order(15)
control; surfaced as RECOMMENDED-NEW-TICKET entry 1 of
`.scratch/tick-review-M1-785-r1.txt` and dispositioned to its own ticket by
the user on 2026-08-07.

This is a redaction + audit-visibility gap, not a flag bypass: the straddle
guarantees a first-pass row, `stage1_flagged` derives from any row
(`Stage1Pipeline.java:447`), so the post stays flagged and Stage 2 still
runs (the r1 verdict's FALSIFIED-AND-DROPPED entries prove both). The cost
is that every downstream reader of `post.body` — tagger, classifier,
embedding, translator, `search_tsv`, `/saved` snapshots, `DisplayHeadline`
fallback — receives the decoded payload text literal, and `/quarantine list`
never shows which rule the payload tripped.

**Ordering (user decisions, binding).** `blocked_by: [M1-784]` — that ticket
changes what becomes literal text in the column and its `Stage1BodyTextIT`
pins land first; this diff and M1-784 also edit the same `handleSuccess`
region. **M1-786 should gain `blocked_by: [M1-787]`** — verified from
M1-786's binding ticket text (its Approach steps 2–3: the remediation job
"call[s] M1-784's sink and M1-785's scan through the same entry point
`Stage1Pipeline` uses", scanning converted bodies that already carry
markers, with marker byte-exactness as its own P7; its out_of_scope forbids
any second decoder; no remediation code exists yet — `Glob
Stage1BodyRemediation*` returns nothing). A remediation run under today's
whole-drop would suppress rows and redactions for straddled payloads across
the stored corpus, and M1-786's at-most-once marker column makes that
permanent. Applying the `blocked_by` edit to M1-786 is the driver's call,
not this diff's.

## Root cause

`handleSuccess` filters second-pass candidates at `Stage1Pipeline.java:432`–`434`
through `withoutMatchesTouchingAPlaceholder` (`:467`–`495`): protected spans
are located from the first pass's emitted placeholder ids (`:472`–`479`),
and any candidate with a strict interval overlap (`:485`) is excluded from
the kept list — so it appears in neither the `finalBody` replacement loop
(`:436`–`445`) nor `rowsToInsert`. Both the redaction and the row are lost,
verified against the brief with no discrepancy. Trace of the reproduction
body (`&amp;#105;gnore\nsystem: previous instructions`): first pass matches
only rule 3 (`system:` — no `ignore` substring exists pre-decode), the
marker replaces it, the sanitize step decodes `&#105;` → `i`, and the second
pass's rule-1 match spans `ignore\n[marker] previous instructions` (window
`\n` + 37 + space = 39 ≤ 40) — overlap, dropped.

The drop's purpose is real: `approve_quarantine` restores by literal
`replace(body, '[REDACTED:' || v_placeholder_id || ']', v_original_html)`
(`V69__approve_quarantine_verdict_owed_guard.sql:150`), so overwriting one
marker byte turns an admin restore into a silent no-op (the family
analysis's marker-protection pitfall, which motivated the drop). The defect
is the granularity, not the goal: only the marker's own bytes need
protection, yet the whole match — including the payload bytes around the
marker — is exempted from recording and redaction. That contradicts:

- `docs/spec/security.md` §Ingest pipeline (:156–:159): "Matches are
  recorded as quarantine spans and replaced in the body with a structured
  placeholder" — the straddled match is neither recorded nor replaced; and
  (:169–:170) "Stage 1 never blocks release on its own — it scrubs and
  routes to review" — the drop neither scrubs nor routes.
- `docs/spec/security.md` §Quarantine workflow (:1332–:1335): "Every Stage 1
  or Stage 2 hit creates a quarantine row holding span offsets, a
  placeholder id, the verbatim original."
- `docs/design/04-security.md` §4.2 step 3 (:89–:92): "For each match:
  Record `(span_start, span_end, rule_id)` … Replace the match … Insert a
  row into `quarantine`."

No test pins the drop (@Order(16) asserts only row non-emptiness and
placeholder survival), so the fix modifies no pre-existing assertion.

## Pitfalls

- P1: Damaging a first-pass marker byte — the hazard the whole-drop was
  built against. One overwritten byte of the 37-char marker makes the
  placeholder-keyed restore (`V69…sql:150`) a silent no-op with no failing
  test. Segments must exclude every protected byte; boundary adjacency
  (candidate sharing an endpoint with a marker span, no shared bytes) is
  NOT overlap under the existing strict predicate (`:485`) and those
  candidates must keep flowing through whole, as today.
- P2: Locating protected spans by bracket SHAPE instead of emitted ids. A
  feed body can embed literal `[REDACTED:<26 base32 chars>]`-shaped text; a
  shape-keyed splitter would treat attacker bytes as protected and carve
  them OUT of the redaction — re-opening the suppression this ticket closes,
  now directly attacker-authored. The current code's property ("Spans are
  located from the ids actually emitted, never by matching bracket text",
  `:464`–`:465`, loop `:472`–`479`) must survive the rework; per-row id
  randomization is the spec's stated defense against pre-crafted
  placeholders (`docs/spec/security.md` §Quarantine workflow :1374–:1378).
- P3: Emitting a marker + row for an EMPTY segment. Where a candidate's
  boundary coincides with a marker boundary, or (a degenerate corner) the
  candidate lies wholly inside protected spans, zero-length sub-spans
  appear; replacing one would fabricate a redaction of nothing, with
  `original_html = ''`. Only non-empty sub-spans get a marker and a row; a
  candidate with no non-empty sub-span yields nothing (correct: the only
  bytes it matched are marker bytes — today's drop behavior is right
  exactly there).
- P4: Buying the segmentation with budget or scan the spec does not grant.
  The shared per-input deadline and match allowance (`ScanBudget`,
  `:807`–`831`) are `docs/spec/security.md` §Ingest pipeline's per-input
  bound — the very bound M1-785 hoisted to `process()`; the r1 review
  bounded the filter's cost when falsifying its DoS finding, and that bound
  must survive. Segmentation must be arithmetic over already-found
  candidates and already-located spans: no `Matcher` use, no re-scan, no
  budget charge. The row bound stays a small constant multiple of the
  existing cap: candidates (m2) and protected spans (m1) are each
  pairwise-disjoint interval families with m1 + m2 ≤ max-matches, each
  overlap incidence consumes a distinct interval end, so segments ≤
  m2 + (m1 + m2) and total rows ≤ 2·(m1 + m2) ≤ 2·max-matches, all inside
  the one existing transaction.
- P5: `Stage1Result.redactedBody` diverging from the column — the
  hand-off-divergence hazard M1-785 pinned with @Order(17), re-armed by any
  new replacement path. Its contract is "what `post.body` now holds in the
  DB" (`:779`–`786`). Segments must flow through the existing `finalBody`
  loop that both stores and returns one string — never a parallel weave.
- P6: Leaving the class javadoc describing the drop. The step list documents
  "a match that straddles an existing `[REDACTED:<id>]` marker is dropped
  whole" (`:86`–`90`) and the method javadoc says "Drop any second-pass
  match…" (`:460`–`466`); the r1 review made exactly this
  stale-step-list mistake a REWORK item once already on this class.
- P7: Offset and ordering bugs in the split. `firstPassPlaceholderIds` is
  appended inside the right-to-left replacement loop (`:403`–`407`), so
  id-located protected spans arrive in REVERSE positional order — the
  splitter must sort spans by start before clipping. The returned Match
  list must stay sorted ascending by start (candidates already are,
  `:371`–`383`; segments within a candidate must be emitted in order) so
  the downstream right-to-left replacement (`:437`) keeps earlier offsets
  stable; a mis-ordered list lands markers mid-text or corrupts a marker.

## Approach

Derived from `docs/spec/security.md` §Ingest pipeline and §Quarantine
workflow: a match is recorded as quarantine span(s) and replaced with the
placeholder; the restore is placeholder-keyed, so each recorded span must
be exactly the bytes its marker replaced. The user-selected direction —
redact the non-marker segments around the placeholder — satisfies both
commitments and, verified below, breaks no restore semantics, so the
fallback (record the row without redacting) is not taken.

**Design: split, don't drop.** Rework `withoutMatchesTouchingAPlaceholder`
into a splitter with the same signature (`List<Match>` in → `List<Match>`
out). For each candidate: no overlap with any protected span → return it
unchanged (single segment = itself, byte-identical behavior to today
including adjacency, P1); overlap → emit one `Match` per maximal NON-EMPTY
sub-span of `[start, end)` not covered by a protected span, carrying the
candidate's `ruleId`, the sub-span offsets into the scanned string, and the
verbatim sub-span substring (P3). The existing `Match` record (`:685`)
already carries exactly these fields. Downstream, `handleSuccess`'s
existing loop (`:436`–`445`) weaves one marker and inserts one quarantine
row per list element — segments flow through it untouched, which is what
makes P1/P5 structural rather than re-proven: markers are never overwritten
because no segment covers a protected byte, and the stored string and
`redactedBody` remain the same `finalBody`.

**Restore semantics, verified sound (why the fallback is rejected).**
`approve_quarantine` is a literal, placeholder-keyed, order-independent
replace (`V69…sql:150`); ids are per-row SecureRandom
(`PlaceholderIds.java`). A segment row restores exactly its sub-span text;
approving every row of the straddle reconstructs the full pre-redaction
stretch (e.g. `ignore\n` + `system:` + ` previous instructions`); approving
any subset is coherent because each marker occurs once and maps to one
verbatim original. `span_start`/`span_end` index the scanned string exactly
as M1-785's second-pass rows already do — an admin-review aid, sound
because the restore is placeholder-keyed (M1-785 Approach step 4). The
fallback would satisfy "recorded" but leave the decoded payload literal in
the column, failing §Ingest pipeline's "replaced in the body" — worse on
the redaction half of the gap for no gain.

**Files to touch**

- `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java`
- `infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java`

**Steps, in order**

1. Convert the reproduction marker (workflow §0): append the three tests at
   @Order(19)–(21) and run them RED against the M1-784 pipeline — the
   reproduction and the two-marker case fail on the drop (0 payload-rule
   rows, literal payload text); the fake-marker case passes on the drop and
   exists to pin the rework (see Verification).
2. Rework the filter into the splitter, keeping the id-keyed span-location
   loop (`:472`–`479`) verbatim (P2) and the empty-list fast path (`:469`),
   sorting the located spans by start (P7), clipping candidates to
   non-empty non-marker sub-spans (P3), and emitting the output list in
   ascending start order (P7). Rename the method to say what it now does
   (e.g. `splitMatchesAroundPlaceholders`); it stays `private static`, one
   call site (`:432`).
3. Change nothing else in `handleSuccess`: the replacement loop, row
   inserts, `flagged` derivation (`:447`), transaction shape (`:449`–`454`)
   and `originalBody` hand-off (`:457`) are untouched (P5), and no
   `ScanBudget` interaction is added (P4).
4. Update the class javadoc second-scan bullet (`:86`–`90`) and the method
   javadoc (`:460`–`466`) to describe per-segment redaction; delete
   "dropped whole" (P6).

**Controls to preserve (engineering-rules §10)** — the path being
re-parameterized is "second-pass match disposition":

- Marker byte-integrity for the placeholder-keyed restore (`V69…sql:150`) —
  today carried by the whole-drop; carried forward by segment exclusion of
  protected bytes. The pinning test @Order(16) stays green UNCHANGED — it is
  the control, not a casualty; the reworked path must satisfy it with more
  rows, not by retargeting it.
- Id-keyed (never shape-keyed) protected-span location (`:463`–`479`) —
  carried verbatim into the splitter; newly pinned by the fake-marker test.
- One per-input deadline + match allowance shared by both scans
  (`:292`–`294`, `:807`–`831`) — untouched; `Stage1SharedScanBudgetIT` and
  `Stage1MatchOverflowIT` unchanged. The unit stays "regex matches found",
  never "segments emitted".
- Fail-closed dispatch order — the second scan stays after `safeSanitize`
  so a sanitizer throw still unwinds to `handleSanitizerException` before
  any second-pass work; this diff does not move the call; @Order(12)/(13)
  unchanged.
- `stage1_flagged` = any row (`:447`); `post.status` stays `RAW`;
  `Stage1Result.originalBody` stays `normalized` (Stage 2's judge view,
  `docs/spec/security.md` §Ingest pipeline) — all untouched.
- `quarantine.original_html` = the verbatim bytes the marker replaced —
  per segment row, exactly the sub-span substring, never the whole match
  and never marker bytes (the granularity IS the control: a whole-match
  `original_html` on a segment marker would make approve re-insert marker
  text).
- The first scan is not moved, narrowed or reordered; M1-784's
  `Stage1BodyTextIT` pins and `Stage1PipelineIT` @Order(1)–(18) all pass
  unchanged.

**Pitfall → mitigation**: P1 → step 2 clipping + @Order(16) unchanged;
P2 → step 2 (id-keyed loop kept) + fake-marker test; P3 → step 2 non-empty
rule; P4 → step 3 + budget ITs unchanged; P5 → step 3 (existing finalBody
loop) + equality assert; P6 → step 4; P7 → step 2 sort + ordered emission.

## Definition of done

- The reproduction passes: a payload straddling one first-pass marker gets
  per-segment quarantine rows (byte-exact `original_html`) and no literal
  payload text survives in `post.body`.
- A payload straddling two markers is fully segmented: 3 segment rows, all
  placeholders verbatim, every `original_html` non-empty.
- Fake `[REDACTED:…]`-shaped body text shields nothing: it is redacted with
  the payload, while real emitted markers survive byte-exact.
- @Order(15)–(18), `Stage1SharedScanBudgetIT`, `Stage1MatchOverflowIT` and
  `Stage1BodyTextIT` (12/12) pass unchanged.
- The class and method javadoc describe the split; "dropped whole" is gone.
- `mvn verify` from the repo root is green.

## Verification

- reproduction → `Stage1PipelineIT.straddlingPayloadIsStillQuarantinedAndRedacted`
  — feeds @Order(16)'s exact hostile body
  (`&amp;#105;gnore\nsystem: previous instructions`); asserts 2
  `stage1.ignore_previous_instructions` rows with `original_html`
  byte-exact `"ignore\n"` and `" previous instructions"` (byte-exact
  originals + marker-verbatim body TOGETHER imply the literal-replace
  restore reconstructs the pre-redaction text), 1 impersonation row, stored
  body containing no literal `ignore` / `previous instructions`, all
  placeholder ids verbatim, status RAW, flagged TRUE,
  `result.redactedBody()` equal to the column (P5). RED on the drop: 0
  payload-rule rows, payload text literal.
- P1, P3, P7 → `Stage1PipelineIT.payloadStraddlingTwoPlaceholdersIsFullyRedacted`
  — feeds `&amp;#105;gnore\nsystem: previous\nsystem: instructions`: the
  first pass emits two markers, the second-pass rule-1 match fills BOTH
  `.{0,40}` windows with a marker (`\n` + 37 + space = 39 each); asserts 2
  impersonation + 3 segment rows (`"ignore\n"`, `" previous\n"`,
  `" instructions"`), all 5 placeholders verbatim in the stored body, no
  literal payload word survives, every `original_html` non-empty (P3). A
  wrong-order or unsorted-spans split (P7) shifts offsets and fails the
  placeholder-verbatim or leftover-text asserts; RED on the drop.
- P2 → `Stage1PipelineIT.fakePlaceholderShapedTextCannotShieldAPayload` —
  feeds `&amp;#105;gnore [REDACTED:AAAAAAAAAAAAAAAAAAAAAAAAAA] previous
  instructions\nsystem: x` (fixed all-`A` fake id: deterministic, and the
  base32 alphabet cannot complete any rule inside a real id's span);
  asserts the fake marker text does not survive in `post.body`, a
  `stage1.ignore_previous_instructions` row exists, and the REAL first-pass
  placeholder (from the `system:` line) survives verbatim. Passes on the
  drop too — it is the guard for the rework: a shape-keyed splitter treats
  the fake bytes as protected, splits around them, and the surviving fake
  text fails the first assert. Mutation named per assertion-adequacy:
  replace the id-keyed location loop with a
  `\[REDACTED:[A-Z2-7]{26}\]` pattern scan and this test fails.
- P4 → `Stage1SharedScanBudgetIT.secondScanSharesTheSinglePerInputWatchdogAndMatchBudget`
  and `Stage1MatchOverflowIT.matchOverflowSealsPostAtQuarantinedAndSkipsRedactPath`
  pass unchanged — a segmentation that re-scans or charges the budget
  shifts the overflow trip point and fails them; the hostile
  worst-case shape (alternating cheap triggers and payloads maximizing
  segments) stays bounded at 2·max-matches rows because both interval
  families are budget-capped before the splitter runs.
- P5 → the reproduction's `redactedBody()`-equals-column assert plus
  @Order(17) unchanged.
- P6 → `grep -n "dropped whole"
  infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java`
  returns nothing; `grep -n "second scan"` still hits inside the class
  javadoc (the r1 rework's probe stays satisfied).
- P1 (control) → @Order(16)
  `secondPassRedactionNeverOverwritesAFirstPassPlaceholder` passes
  UNCHANGED — every placeholder id must appear verbatim in the final body;
  a segment covering a protected byte fails it.
- M1-784 non-interference → `Stage1BodyTextIT` 12/12 unchanged (verified at
  analysis: none of its bodies produces a second-pass match overlapping a
  first-pass marker, so no assertion there can see this change).
- full suite → `mvn verify` from the repo root.

## Out-of-scope

The pre-existing restore no-op where the sanitizer deletes an element
containing a first-pass marker (M1-785 §Review observations, entry 2) is a
different mechanism on a different step and stays untouched — do not chase
it here.

M1-786 is not edited. This ticket records the verified finding that
M1-786's remediation re-runs this scan-and-redact path over stored bodies
that already carry markers (its Approach steps 2–3 and P7), so its
`blocked_by` should gain M1-787 — the driver applies that edit.

`Stage1RegexSet` is closed; no decode pass, no budget, no config key is
added; `ScanBudget` semantics are byte-identical. The OWASP policy, the
plain-text sink, `safeSanitize` and the fail-closed handlers are not
touched. `approve_quarantine` (V69) is not touched — its literal replace is
the invariant the segmentation is designed around. Nothing in
`infochat-provider/**` changes.

No pre-existing test is modified; the three new methods are appended at
@Order(19)–(21) beside the M1-785 straddle pin they extend. Historical
documents that describe the drop as designed (M1-785's ticket body, the
family analysis `docs/plan/m1/tick-analysis/ingest-corrupts-post-body-text.md`
Option G) are records of their tickets and are not rewritten.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-787-*.md
```
