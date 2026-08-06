---
id: M1-785
title: "Stage 1 must scan the body text it stores"
status: done
created: 2026-08-06
last_updated: 2026-08-07
flow: tick
reproduction: Stage1PipelineIT.doublyEncodedEntityInjectionIsDetectedNotBypassed
              — new test method this ticket adds, seeding body
              "&amp;#105;gnore previous instructions" and asserting
              stage1_flagged=TRUE with one quarantine row carrying
              rule_id stage1.ignore_previous_instructions.
              Probe: ./mvnw -B -pl infochat-collector -am verify
              -Dit.test=Stage1PipelineIT -Dfailsafe.failIfNoSpecifiedTests=false
              Expected wrong behavior on main, derived from
              Stage1Pipeline.java:273 (single unescapeHtml4 pass) and
              Stage1Pipeline.java:422 (the OWASP parse decodes the second
              layer; letters are not re-escaped): post.body reads
              "ignore previous instructions", stage1_flagged=FALSE, 0
              quarantine rows. NOT YET RUN by the analyst — write it and
              confirm RED before /tick start (workflow §0).
analysis_ref: docs/plan/m1/tick-analysis/ingest-corrupts-post-body-text.md
blocked_by: []
decomposed_from: M1-776
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java
  # Added at implementation time under the Out-of-scope clause "if the
  # implementor prefers a new IT class, the method names above are still the
  # contract". The P1 budget test needs a cap far below the production 1000
  # (a body big enough to exceed 1000 across two scans trips the 100ms
  # watchdog first and asserts the wrong failure mode), that cap is
  # Quarkus config fixed per test class, and Stage1PipelineIT must keep
  # the production default for its other 18 cases.
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1SharedScanBudgetIT.java
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the stored representation of post.body — swapping the OWASP output sink
    for a plain-text one is M1-784; this ticket leaves the sink untouched
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1BodyTextIT.java
    — the reproduction file for M1-784; do not add, commit or green it here
  - decoding entities to a fixpoint, or adding any decode pass beyond the
    existing single unescapeHtml4 at Stage1Pipeline.java:273
  - widening, narrowing or reordering Stage1RegexSet — the pattern set is a
    closed spec-level commitment
  - remediating rows already stored (M1-786)
  - infochat-provider/**
acceptance:
  - Stage1PipelineIT.doublyEncodedEntityInjectionIsDetectedNotBypassed passes —
    the reproduction; body "&amp;#105;gnore previous instructions" now yields
    stage1_flagged=TRUE and exactly one quarantine row with rule_id
    stage1.ignore_previous_instructions
  - Stage1PipelineIT.secondPassRedactionNeverOverwritesAFirstPassPlaceholder passes
    (P2) — failure mode: a body whose first-pass hit sits inside a second-pass
    rule's .{0,40} window; asserts each quarantine.placeholder_id for the post
    appears verbatim in the final post.body, so approve_quarantine's literal
    replace can never become a no-op (docs/spec/security.md §Quarantine workflow)
  - Stage1PipelineIT.secondScanSharesTheSinglePerInputWatchdogAndMatchBudget passes
    (P1) — asserts the wall-clock deadline and the remaining max-matches budget
    are computed once per process() call and consumed by both scans, never once
    per scan (docs/spec/security.md §Ingest pipeline)
  - Stage1PipelineIT.stage1ResultRedactedBodyEqualsTheStoredColumn passes
    (P8) — asserts Stage1Result.redactedBody() equals the value SELECTed back
    from post.body on a post whose only hit came from the second scan
  - Stage1PipelineIT.legitimatelyEscapedProseIsNotOverDecoded passes (P10) —
    failure mode: a body containing "&amp;lt;" as ordinary prose must still
    read "&lt;" in post.body, never "<"
  - Stage1MatchOverflowIT.matchOverflowSealsPostAtQuarantinedAndSkipsRedactPath passes
    unchanged, and Stage1PipelineIT @Order(8) (<<<UNTRUSTED>>>), @Order(9)-(11)
    (entity pre-decode) and @Order(12)-(13) (sanitizer-exception fail-closed)
    pass unchanged (P3, P6)
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java
      (four new @Test methods appended at @Order(15)-(18); no existing
      method's body or assertions are modified)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1SharedScanBudgetIT.java
      (the fifth method — secondScanSharesTheSinglePerInputWatchdogAndMatchBudget
      — in a new tiny-cap-profile class; see the files_scope note)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Ingest pipeline
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §Quarantine workflow
  - docs/design/04-security.md §4.2 Layered ingest security
decision_refs:
  - D20
  - D22
reviews:
  - round: 1
    date: 2026-08-07
    verdict: REWORK
    checks: SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY FAIL, SCOPE PASS
    diff_stats: 5 files, +546/-38
    note: >-
      Reclassified APPROVE-WITH-FIXES under the 35bd5564 flow rules
      (user-approved 2026-08-07): the single finding is low-severity,
      comment-only, mechanically probed. Fix applied in-band (class javadoc:
      second-scan step added between OWASP and UPDATE; step-6 bullet
      qualified; two placement constraints added to "Step order"). Probes
      pass — grep "second scan" hits Stage1Pipeline.java:107 inside the
      class doc; "OWASP-sanitized form" no longer stands unqualified.
      Fix hunks verified 100% comment lines; `mvnw -pl infochat-collector
      -am test-compile` green; the r1 green full-suite log
      (.scratch/tick-test-M1-785-r1.log, BUILD SUCCESS, 7/7 modules)
      remains the log of record.
clarity_check:
  date: 2026-08-06
  verdict: PASS
  warnings:
    - >-
      Self-check: every file:line citation in Root cause and Pitfalls
      spot-checked against the code before start — :273 is a single
      unescapeHtml4, :277 scans `normalized`, :427 stores sanitizedRedacted,
      :330/:337 compute the deadline and match cap per call, :542 is the
      package-private sanitize seam, OWASP_POLICY is FORMATTING+BLOCKS+LINKS,
      and V69__approve_quarantine…sql:150 is the literal replace(body,
      '[REDACTED:. All hold.
    - >-
      Self-check: the out_of_scope claim that no Stage1BodyTextIT case greens
      from this ticket alone was verified, not assumed — on the unchanged
      OWASP sink the backtick payload is re-encoded to &#96; before the second
      scan would see it, so rule 6 still cannot match. The chosen
      reproduction (&amp;#105;gnore) works because letters are not re-escaped.
    - >-
      Deviation recorded: the reproduction named in `reproduction:` does not
      exist yet (the field says so). It is written and confirmed RED as the
      first implementation step on this branch, before any production change,
      rather than before `start` — workflow §0's evidence-before-fix property
      is preserved; its literal before-filing ordering was not available
      because the analyst drafted the ticket that names the test.
    - >-
      Housekeeping: Stage1BodyTextIT (M1-784's reproduction, out_of_scope
      here) is parked at .scratch/parked-for-M1-784/ for the duration of this
      ticket — left in the source tree its 8 red cases would make the
      repo-root `mvn verify` gate red for reasons outside this ticket.
  blockers: []
---

# M1-785: Stage 1 must scan the body text it stores

## Context

Stage 1 runs its prompt-injection regex set over one string and writes a
different one to `post.body`. The regex set scans `normalized`
(`Stage1Pipeline.java:277`); the column receives `sanitize(redacted)`
(`Stage1Pipeline.java:427`). Everything the OWASP step does between those two
points — most importantly, decoding HTML character references as part of
parsing — is outside the scan. A payload encoded one layer deeper than the
single `unescapeHtml4` pass at `:273` therefore reaches the column decoded,
with `stage1_flagged=FALSE` and no quarantine row: redteam M1-032 Finding 1,
which that pre-decode exists to close, reopened by adding one `&amp;`.

This ticket adds the missing half of the invariant and changes no stored
representation. It lands before M1-784, which changes what the stored string
is: with this guard in place, that change needs no scanning code of its own.
Full derivation, the traced payloads and the rejected alternatives are in
`docs/plan/m1/tick-analysis/ingest-corrupts-post-body-text.md`.

## Root cause

`Stage1Pipeline.process` (`Stage1Pipeline.java:266`–`286`) decodes entities
once (`:273`), normalizes (`:274`), scans (`:277`), redacts into `normalized`
(`:398`–`408`), then hands the redacted string to `safeSanitize` (`:390` clean
path, `:422` match path) and writes the sanitizer's output (`:427`,
`:587`–`599`).

`OWASP_POLICY` (`:225`–`228`) is an HTML sanitizer: its parse decodes
character references in text, and its renderer re-escapes only HTML-significant
characters. So a character reference whose target is outside that escape set is
decoded and stays decoded in the column. Worked example, matching the
reproduction:

```
raw       &amp;#105;gnore previous instructions
:273      &#105;gnore previous instructions      (unescapeHtml4 is a single pass)
:277      no rule matches — there is no "ignore" substring
:422      the parse decodes &#105; → 'i'; letters are not re-escaped
:427      "ignore previous instructions" stored, stage1_flagged=FALSE, 0 rows
```

`Stage1PipelineIT` @Order(9)–(11) (`:311`–`370`) pin the single-encoded forms
of exactly this vector, which is the evidence that the class of payload is
in-scope for Stage 1 and that only the depth changed.

## Pitfalls

- P1: Giving the second scan its own watchdog deadline and its own match cap.
  `findAllMatchesUnderWatchdog` computes `deadlineNanos` internally
  (`Stage1Pipeline.java:330`) and compares against `maxMatches` per call
  (`:337`), so a second invocation silently doubles both. `docs/spec/security.md`
  §Ingest pipeline commits to a **per-input** wall-clock watchdog; doubling the
  CPU an attacker-chosen body can command trades away a stated bound
  (engineering-rules §2).
- P2: A second-pass redaction that overlaps a first-pass `[REDACTED:<id>]`
  marker. `approve_quarantine` restores with a literal
  `replace(body, '[REDACTED:' || v_placeholder_id || ']', v_original_html)`
  (`V69__approve_quarantine_verdict_owed_guard.sql:150`); overwrite one byte of
  the 37-char marker and the restore silently becomes a no-op, breaking the
  consistency property the pipeline states about itself
  (`Stage1Pipeline.java:420`–`421`, `:443`–`447`) with no failing test.
  Reachable, not theoretical: rules 1/2/4 carry `.{0,40}` DOTALL interstitials
  (`Stage1RegexSet.java:99`–`132`) and the marker is 37 chars.
- P3: "Simplify" by moving the scan to after the OWASP step instead of adding
  one. An HTML parse drops comments outright and mangles `<<<UNTRUSTED>>>`, so
  rule 5 (`Stage1RegexSet.java:134`–`136`) could never fire again and rule 6's
  `<<<UNTRUSTED>>>` alternative (`:139`–`146`) would lose its shape — the reason
  the step order is called load-bearing at `Stage1Pipeline.java:91`–`115`.
  Stage 1 is required to record and route to review
  (`docs/spec/security.md` §Ingest pipeline), so losing the quarantine row is a
  control loss even when the payload is removed from the body anyway.
- P6: Losing the sanitizer-exception fail-closed branch. The second scan must
  sit **after** `safeSanitize` returns, so a throw still unwinds to
  `handleSanitizerException` (`Stage1Pipeline.java:523`–`529`, `:557`–`585`)
  and never reaches the new code. `docs/spec/security.md` §Failure handling
  requires that branch; it is reachable in tests only through the
  package-private `sanitize` seam (`:542`) that
  `Stage1PipelineIT.SanitizerThrowingStage1Pipeline` (`:629`–`641`) overrides.
- P8: `Stage1Result.redactedBody` diverging from the column. Its contract is
  "what `post.body` now holds in the DB" (`Stage1Pipeline.java:714`–`717`); a
  second-pass redaction applied to the DB string but not to the record makes
  the hand-off carrier lie to Stage 2 and to every test that reads it instead
  of the column.
- P10: Reaching for "decode to a fixpoint" instead. It corrupts legitimately
  escaped prose (a post about HTML writing `&amp;lt;` would store `<`) and
  chases obfuscation depth that `docs/design/04-security.md` §4.2 explicitly
  declines to chase ("base64-encoded and otherwise obfuscated injection
  bypasses Stage 1 by design"). The invariant is "scan what you store".

## Approach

Derived from `docs/spec/security.md` §Ingest pipeline: the regex set runs on
the body, matches are recorded as quarantine spans and replaced with
`[REDACTED:<id>]`. The body a consumer reads is the stored one, so that is the
string the guarantee has to hold for.

**Files to touch**

- `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java`
- `infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java`

**Steps, in order**

1. Hoist the scan budget to `process()`: compute one `deadlineNanos` and carry
   one remaining-match allowance for the whole call, and pass both into
   `findAllMatchesUnderWatchdog`. Do this *first* so the second scan cannot be
   added on a per-call budget by accident (P1).
2. In `handleSuccess`, after `safeSanitize(...)` produces the string destined
   for `post.body` and **before** `TransactionHelper.inTransaction`, run the
   same rule set over that exact string under the shared budget. Placing it
   after `safeSanitize` keeps the fail-closed branch first in line (P6);
   placing it before the transaction keeps the existing "never commit half a
   write" property stated at `:416`–`421`.
3. Discard any second-pass match whose span intersects a `[REDACTED:` … `]`
   region already present in that string, then apply the existing
   overlap-resolution and right-to-left replacement to what remains (P2). The
   marker regions are locatable from the first pass's placeholder ids; do not
   re-derive them by pattern-matching arbitrary bracket text.
4. Insert one quarantine row per surviving second-pass match in the same
   transaction as the first pass's rows and the `UPDATE`. `span_start`/
   `span_end` index the final body; that is sound because the restore is
   placeholder-keyed (`V69…sql:150`), and the offsets are an admin-review aid.
5. Set `stage1_flagged` true when **either** scan matched, and return the
   post-second-pass string as `Stage1Result.redactedBody` (P8).
   `Stage1Result.originalBody` stays `normalized` — Stage 2 sees the
   pre-redaction original (`docs/spec/security.md` §Ingest pipeline).
6. A watchdog or overflow trip during the second scan routes to the same
   `handleWatchdogAbort` / `handleMatchOverflow` as before, unchanged
   (`docs/spec/security.md` §Failure handling).
7. Add the five test methods to `Stage1PipelineIT`, appended after @Order(14).
   No existing method is modified.

**Controls to preserve (engineering-rules §10)**

- The first scan is not moved, narrowed or reordered — rule 5 and rule 6's
  `<<<UNTRUSTED>>>` alternative keep the pre-parse form they need (P3);
  `Stage1PipelineIT` @Order(8) is the pin.
- The entity pre-decode at `:273` stays exactly one pass; @Order(9)–(11) pin it.
- `OWASP_POLICY` and the `sanitize` seam are untouched; @Order(12)/(13) and
  `SanitizerThrowingStage1Pipeline` pin the fail-closed branch (P6).
- NFKC + bidi/zero-width strip stays before the first scan; @Order(4)/(5)/(6)/(14)
  pin it.
- `[REDACTED:<id>]` stays byte-exact and each emitted placeholder stays present
  in the final body (P2).
- The whole-body fail-closed rows keep writing `normalized` as
  `original_html` — the judge's view of the post is unchanged.

**Pitfall → mitigation**: P1 → step 1; P2 → step 3; P3 → step 2 (add, never
move); P6 → step 2 ordering; P8 → step 5; P10 → no decode pass is added
anywhere (out_of_scope).

## Definition of done

- The reproduction passes: a doubly-encoded `ignore previous instructions`
  payload is flagged and quarantined instead of stored decoded and clean.
- A second-pass redaction can never damage a first-pass placeholder, proven by
  a test that feeds a straddling payload.
- One wall-clock deadline and one match budget cover the whole `process()`
  call, not one per scan.
- `Stage1Result.redactedBody` equals the stored column.
- Legitimately escaped prose is not over-decoded.
- The `<<<UNTRUSTED>>>`, entity-pre-decode and sanitizer-exception tests pass
  unchanged.
- `mvn verify` from the repo root is green.

## Verification

- P1 → `Stage1PipelineIT.secondScanSharesTheSinglePerInputWatchdogAndMatchBudget`
  — feeds a body whose combined match count across both scans exceeds
  `infochat.security.stage1.max-matches`; asserts the post fails closed once
  with a single `match_overflow` row, i.e. the budget was never refreshed for
  the second scan. `Stage1MatchOverflowIT` (cap 3, six impersonation lines)
  must stay green as the first-scan control.
- P2 → `Stage1PipelineIT.secondPassRedactionNeverOverwritesAFirstPassPlaceholder`
  — feeds a body whose first-pass hit lands inside a second-pass rule's
  `.{0,40}` window; asserts each `quarantine.placeholder_id` for the post
  appears verbatim in the final `post.body`, so no marker was partly consumed.
- P3 → `Stage1PipelineIT` @Order(8) — a literal `<<<UNTRUSTED>>>` must still
  produce its delimiter-injection quarantine row and must not survive in the
  body; a `<!-- … -->` body must still produce its comment-rule row.
- P6 → `Stage1PipelineIT` @Order(12) and @Order(13) via
  `SanitizerThrowingStage1Pipeline` — a seam throw must still seal the post at
  `status='QUARANTINED'` with exactly one `sanitizer_exception` row and must
  never reach the second scan.
- P8 → `Stage1PipelineIT.stage1ResultRedactedBodyEqualsTheStoredColumn` — on a
  post whose only hit comes from the second scan, asserts
  `result.redactedBody()` equals the value SELECTed back from `post.body`.
- P10 → `Stage1PipelineIT.legitimatelyEscapedProseIsNotOverDecoded` — a body
  carrying `&amp;lt;` as ordinary prose must still read `&lt;` in the column;
  it must not be decoded to `<`.
- reproduction → `Stage1PipelineIT.doublyEncodedEntityInjectionIsDetectedNotBypassed`.
- full suite → `mvn verify` from the repo root.

## Out-of-scope

The stored representation is not touched here: the OWASP policy, the sanitizer
call and the string it produces stay exactly as they are. Making `post.body`
plain text is M1-784, and `Stage1BodyTextIT` is that ticket's reproduction —
none of its eleven cases goes green from this ticket alone, so do not add,
commit or chase that file here.

No decode pass is added and the existing single `unescapeHtml4` at
`Stage1Pipeline.java:273` is not repeated or looped: chasing arbitrary encoding
depth is out of policy per `docs/design/04-security.md` §4.2.

`Stage1RegexSet` is not edited — the pattern set is a closed spec-level
commitment and changing it is a spec amendment.

Rows already written are not remediated (M1-786), and nothing in
`infochat-provider/**` is touched.

No pre-existing test is modified. The five new methods are appended to
`Stage1PipelineIT` after @Order(14) because they belong beside the
single-encoded entity-bypass regressions at @Order(9)–(11) that they extend;
if the implementor prefers a new IT class, the method names above are still the
contract.

## Review observations (round 1, dispositioned per the 35bd5564 rules)

- **Relayed to the user (DECIDE-BEFORE: M1-784):** the placeholder-overlap
  rule is attacker-selectable — a feed operator can plant a cheap first-pass
  trigger inside a payload's `.{0,40}` window so the second-pass match
  straddles the `[REDACTED:<id>]` marker and is dropped whole (no redaction,
  no quarantine row for the payload's rule; the post stays flagged via the
  first-pass row). Demonstrated by
  `Stage1PipelineIT.secondPassRedactionNeverOverwritesAFirstPassPlaceholder`.
  Not a defect of this diff (main stores the same string; the Approach
  mandates the drop), but M1-784 widens what becomes literal text in the
  column. Recommendation delivered 2026-08-07: own small ticket via
  `/tick analyze` after M1-784 (direction: redact the non-marker segments
  around the placeholder; fallback: record the quarantine row without
  redacting); user decision pending.
- **Recorded, no decision requested (TOUCHED-BY-THIS-DIFF: no):**
  pre-existing — a first-pass marker landing inside an element the sanitizer
  deletes leaves a quarantine row whose `approve_quarantine` restore is a
  silent no-op (marker gone from the body, row still flips to APPROVED).
  Exists on main independently of this diff.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-785-*.md
```
