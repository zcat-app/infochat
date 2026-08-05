---
id: M1-766
title: "Anchor-first degraded renderers"
status: done
created: 2026-08-04
last_updated: 2026-08-05
blocked_by:
  - M1-759
files_budget: 21
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  - docs/spec/security.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseRefusalDegradeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDegradedRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestBudgetScopingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    NEW PROJECTIONS. Both surfaces already receive
    `EligiblePostQuery.Post` instances — `DegradedDigestRenderer.render`
    takes `List<EligiblePostQuery.Post>` and
    `SummaryProseGenerator.degradedProseFor` iterates `cluster.posts()` —
    and M1-759 already added the anchor to that record and to all three
    projections that populate it. No SQL changes.
  - >-
    CALLER BEHAVIOUR BEYOND PASSING THE LANGUAGE. The four caller files
    are in `files_scope` for the `scopeLanguage` argument and nothing
    else: each already holds the value, so the edit is one added
    argument per call site. Their own rendering, budgeting, degradation
    and translation decisions are untouched — in particular
    `DigestRenderer.appendHeadlines`' display-hit leg and translation
    budget, and `ClusterBlockRenderer`'s degraded skip, stay exactly as
    M1-759 / M1-769 left them.
  - >-
    ANY TRANSLATOR CALL. The degraded path exists BECAUSE the LLM is
    unavailable or refused. Reading the anchor column is free and is the
    whole reason this surface can have it; invoking
    `runForDisplayHit` here would put a generative call on the one path
    guaranteed not to have a working model. The reader-language line is
    the anchor as stored, never a display-time translation.
  - >-
    `SummaryProseGenerator.buildPrompt` and the summarizer/roll-up
    PROMPT inputs. Prompt text stays untranslated and anchor-free
    (M1-747); only `degradedProseFor`, which renders to the user, changes.
    `SummaryProseGenerator` appears in `files_scope` for that one method.
  - >-
    THE BLOCK SHAPE ITSELF. M1-759 owns the bracket invariant, the
    suppression rule and the line order. This ticket adopts them; it does
    not redefine them. CARVE-OUT (redteam 2026-08-05 finding 1): the
    SANITIZE UNIT inside `DisplayHeadline.derive` IS in scope — the two
    lines become one sanitize call over the joined pair. That changes
    what the sanitizer sees, not what the reader sees: the bracket
    invariant, the suppression rule and the line order are untouched,
    and the only rendering difference is the redaction a cross-line
    closed-list entry now produces. Note the two surfaces have their own
    line templates (`DegradedDigestRenderer` renders
    `headline — sourceDisplayName` then the URL on its own line;
    `degradedProseFor` joins headline and url with `" — "` and appends
    the uid), and per M1-759's CONSISTENT-ACROSS-SURFACES item what
    must match is the DERIVATION and the invariant, not the bytes.
  - >-
    `DisplayHeadline.of(Post, …)`'s existing behaviour for any caller
    outside `files_scope`. After this ticket the only remaining callers
    of the original-only entry point are prompt builders, which must
    keep it.
acceptance:
  - >-
    A CLOSED-LIST ENTRY SPANNING THE ANCHOR AND THE ORIGINAL IS REDACTED
    AND AUDITED (redteam 2026-08-05 round 1, finding 1, medium/INJECTION).
    `DisplayHeadline.derive` sanitizes the two lines INDEPENDENTLY, so a
    flag-bearing entry with its command word on one line and its flag on
    the other matches neither call — no `[redacted command]`, no
    `LLM_OUTPUT_SANITIZED` row. `docs/spec/security.md` accepts that
    residual only ACROSS POSTS, and only because merging would let one
    publisher's flag-span swallow another publisher's bytes; the anchor
    and the original are the SAME publisher's field pair, so that
    justification does not reach here. Fix at the shared derivation so
    all FIVE surfaces are covered at once, not just this ticket's two:
    the pair takes ONE sanitize call over the two flattened lines joined
    by a renderer-authored `\n` (unforgeable — every operand has already
    been through `flattenToOneLine`), and the lines are split back out
    afterwards. The sanitize UNIT therefore becomes "one author's field
    PAIR", never two authors' or two posts' bytes, so M1-697's
    cross-post span bug stays closed.
  - >-
    A DISPLAY NAME CANNOT SUPPLY THE FLAG HALF (redteam 2026-08-05 round
    1, finding 2, medium/INJECTION). `SourceUpsertService
    .acceptableOverride` rejects a slash anywhere because a
    bot-command-shaped `--name` is copy-paste-executable by a bot admin
    — its own javadoc says so — but the FLAG half of a flag-bearing
    closed-list entry carries no slash, so `--name "Acme --all News"` is
    stored and rendered unsanitized beside a sanitized `/list-sources`
    headline, yielding a dispatchable line with no redaction marker and
    no audit row. Extend the SAME guard, at the SAME write boundary the
    spec already names as sufficient ("constraining the single produced
    value covers every surface that later renders it"), to reject a
    flag-shaped token alongside the slash. Reject, never rewrite — the
    existing javadoc's reasoning for discarding the whole override
    applies unchanged.
  - >-
    ACCEPTED RESIDUAL, STATED: the write-boundary reject governs values
    produced from now on; a display name ALREADY stored with a
    flag-shaped token keeps rendering until it is re-validated. Not
    closed here — re-validating stored rows is separate work — and the
    exposure is bounded because v1 sources come from
    `bootstrap-sources.json` plus `/add-source`.
  - >-
    AN ANCHOR THAT CANONICALIZES AWAY DEGRADES, IT DOES NOT SUPPRESS THE
    HEADLINE (redteam 2026-08-05 round 2, out-of-model 1 — a NEW failure
    mode the round-1 fix created, dispositioned fix-in-scope). An anchor
    of only zero-width codepoints passes `isBlank()` (they are not
    whitespace) and survives while nothing matches, because `sanitize`
    returns the caller's own bytes on a no-match; the moment any
    closed-list token matches ELSEWHERE in the pair it returns the
    CANONICAL form instead, where `stripBidiAndZeroWidth` has erased the
    anchor. `AnchoredHeadline.isEmpty()` keys off `readerLine` alone, so
    an empty reader line would make every caller drop the SURVIVING
    original with it. Degrade to the anchor-absent shape instead.
    Pinned both ways: the match path degrades, the no-match path leaves
    the anchor untouched.
  - >-
    THE SPEC SAYS WHICH TRANSLATION IT MEANS (redteam 2026-08-05 round 2,
    out-of-model 3). The widened wording this ticket introduced —
    "the same field or its translation" — reads as though it might cover
    the PRESENTATION translation. It does not: the display-hit leg
    sanitizes the translator's reply by itself via
    `prepareTranslatedHeadline`, a separate call from the pair call in
    `derive`. Name the ingest anchor explicitly and state the
    display-hit derivation as the residual it is. Repairs an ambiguity
    THIS diff created; the underlying behaviour is pre-existing and no
    code changes.
  - >-
    A REDACTION THAT COLLAPSES THE PAIR DEGRADES SAFELY. When the
    flag-span deletion consumes the joining newline the split yields one
    part, not two; the block then renders as a single primary line with
    no subordinate, and never as a dangling separator, an empty bracket,
    or a silently dropped redaction marker.
  - >-
    THE SPEC DESCRIBES THE SHAPE IT NOW PINS. `docs/spec/security.md`
    §Failure handling still calls the degraded fallback "the headlines +
    URLs + post UIDs degraded form" (line ~1426), which omits the
    bracketed original line this ticket and M1-759 add; and the
    sanitize-unit call-site enumeration still describes these renderers
    as passing "one title per post". Both are updated to the
    field-PAIR unit and the two-line block. Doc-only edit folded in here
    rather than split off, because the drift is an orphan THIS diff
    creates (redteam 2026-08-05 out-of-model item 2).
  - >-
    THE READER LANGUAGE REACHES BOTH SURFACES. Neither
    `DegradedDigestRenderer.render(List<Post>)` nor
    `SummaryProseGenerator.degradedProseFor(Cluster, sanitizer)` takes a
    scope language today, and `DisplayHeadline.usesAnchor` /
    `primaryFor` cannot be called without one — so each grows a
    `scopeLanguage` parameter and every caller passes the value it
    ALREADY holds (`DigestWorker` `meta.language()`; `DigestRenderer`
    `langCode` at all three sites; `SummaryCommandHandler` and
    `ClusterBlockRenderer` `scopeLanguage`). Parameter-threading only:
    no caller resolves, defaults, or derives a language it did not
    already have, and no other signature changes.
  - >-
    BOTH SURFACES SWITCH to the anchor-aware entry point M1-759 added,
    so a non-English post renders its English anchor with the bracketed
    original beneath on the degraded digest and in degraded prose, and
    an unbracketed line still means "already in the reader's language".
  - >-
    ZERO PROVIDER CALLS ON THE DEGRADED PATH — asserted with a spy on
    both surfaces. This is the property that makes the change safe to
    make here at all: the anchor is a column read, and the degraded path
    is by definition running without a usable model.
  - >-
    THE M1-714 OMISSION CONTRACT SURVIVES. Both renderers drop an empty
    headline together with its separator — `DegradedDigestRenderer` so
    the entry leads with the source display name and never a dangling
    `" — "`, `degradedProseFor` so the line opens with its url and the
    uid still identifies it. Adding a second line must not resurrect a
    dangling separator when the headline is empty, nor emit an empty
    bracket `[]`. Pinned by test on both.
  - >-
    THE M1-729 SENTINEL FALLBACK SURVIVES, on the same rule M1-759
    adopts: choose the field (title vs body) from the ORIGINAL, then take
    that field's anchor. A titleless non-English post must still render
    from its body, not from a translated `"untitled"` sentinel.
  - >-
    ORDER UNCHANGED, UNIT WIDENED BY EXACTLY ONE STEP. The order stays
    bound -> flatten -> sanitize -> truncate. The UNIT moves from one
    author's field to one author's field PAIR — that field's stored text
    plus its ingest translation, taking ONE `sanitize` call joined by a
    renderer-authored newline (acceptance item 1 supersedes the original
    "never concatenated" wording, which described the pre-redteam
    behaviour). AND NO FURTHER: never two posts' bytes, never two
    authors', so M1-697's cross-post span bug stays closed — pinned by a
    two-post test on each surface. Per CLAUDE.md §"Preserve the controls
    of a path you replace", the unit is part of the control, which is
    why the widening is stated here rather than left implicit.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  preserves:
    - >-
      M1-714's empty-operand omission contract on both renderers.
    - >-
      The degraded path's no-LLM guarantee — `SummaryProseRefusalDegradeTest`
      pins that a refusal degrades rather than calls again.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
  - D43
reviews:
  - round: 1
    date: 2026-08-05
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 21
      added: 1441
      removed: 67
  - round: 2
    date: 2026-08-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 21
      added: 1478
      removed: 77

overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-05
    category: INJECTION
    severity: medium
    promise: |
      docs/spec/security.md §"Flag position mirrors the parser's own scan":
      "The span's justification holds because every caller's unit of input is
      one author's field ... `DegradedDigestRenderer` one title per post ...
      and degraded prose is DERIVED from the cluster at render and sanitized
      per post title (`SummaryProseGenerator.degradedProseFor`) ... so no
      caller hands the sanitizer a multi-post concatenation." The accepted
      residual is scoped to a command split ACROSS two posts' fields, and is
      accepted because merging would let one publisher's span swallow another
      publisher's bytes.
    gap: |
      Both surfaces now render TWO adjacent separately-sanitized fields per
      post (the anchor and the original), emitted in one delivered message.
      The spec's justification for accepting a split does NOT hold on this
      axis: the anchor and the original are the SAME publisher's field pair
      (title/title_en), so merging them would swallow nobody else's bytes. A
      flag-bearing closed-list entry split across the pair is therefore
      neither redacted nor audit-logged.
    repro: |
      A non-`en` source whose title carries an ingest-translator prompt
      injection can leave the stored pair holding the two halves separately —
      title = "Zpravy dnes --all rano", steered title_en = "/list-sources".
      Neither field alone matches the closed list, so neither per-field
      sanitize call redacts and no audit row is written. On a degraded digest
      the broadcast carries "/list-sources — <source>" over "[... --all ...]";
      a bot admin copy-pasting it dispatches all=true. Bounded by is_admin,
      the same bound the spec accepts for the cross-post case.
    suggested_fix_class: input-sanitization
  - date: 2026-08-05
    category: INJECTION
    severity: medium
    promise: |
      docs/spec/security.md §"LLM output sanitizer": the `--name` display-name
      echo "is closed at the write boundary alone ... because a display name
      has no legitimate slash, constraining the single produced value covers
      every surface that later renders it." Plus §"Flag position mirrors the
      parser's own scan": a flag-bearing entry matches its flag at any
      position in the argument run, precisely so such a line cannot ship
      verbatim with no redaction marker and no audit row.
    gap: |
      The write-boundary constraint removes only the COMMAND-WORD half.
      `SourceUpsertService.acceptableOverride` rejects blank/slash/over-length
      only, so a display name of `Acme --all News` is stored; the renderer
      appends `sourceDisplayName()` UNSANITIZED on the same delivered line as
      the sanitized headline. The delivered message can therefore carry a
      complete dispatchable flag-bearing entry whose flag came from a field
      the sanitizer never sees. PRE-EXISTING in the method — this diff does
      not introduce the unsanitized append — but the diff rewrites that
      method and widens the fields that can supply the command word from one
      (title/body) to two (adding the LLM-authored anchor).
    repro: |
      `/add-source <feed> --tags news --name "Acme --all News"` is accepted
      (no slash, under the cap). A post titled `/list-sources` sanitizes to
      itself (no flag in that field). A degraded digest broadcasts
      "/list-sources — Acme --all News"; a bot admin copy-pasting it yields
      tokens including `/list-sources` and `--all`, and ListSourcesArgs.parse
      sets all=true, dumping the deployment-wide source catalogue. No
      redaction marker and no LLM_OUTPUT_SANITIZED row exists to correlate.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-08-05
    verdict: FINDINGS
    base: 749f4194c99931adda9437e44dc6ffbbb1349159
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-766-2026-08-05.md
    findings_count: 2
    out_of_model_count: 3
    note: |
      Round 1 at the /m1-tick run gate, ahead of review. Both findings are the
      same shape — a flag-bearing closed-list entry whose two halves arrive
      from fields the sanitizer never sees together. Finding 1's mechanism
      (DisplayHeadline.derive's two sanitize calls) is NOT in this diff and is
      already live on three merged surfaces from M1-759; this diff extends it
      to the two degraded ones. Finding 2 is fully pre-existing: the
      unsanitized sourceDisplayName append is unchanged here, and the chain
      runs through SourceUpsertService.acceptableOverride and
      ListSourcesArgs.parse, both outside this ticket. Disposition is the
      user's call.
  - date: 2026-08-05
    verdict: CLEAN
    base: 749f4194c99931adda9437e44dc6ffbbb1349159
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-766-2026-08-05-r2.md
    out_of_model_count: 3
    note: |
      Round 2, re-audit of the remediated diff. Both round-1 findings
      verified genuinely CLOSED, not assumed closed: the adversary attacked
      the fixes as new surface and failed at separator forgery (every
      operand is flattened first), split desynchronisation (no sanitizer
      stage can emit a second newline), cross-post reach, audit-signal loss
      under the single call, and ~8 representation bypasses of
      containsFlagToken. It also confirmed the round-1 tests that pinned the
      gap as intended behaviour were replaced by redaction assertions, with
      no pre-existing security assertion deleted, weakened or retargeted.
      Three out-of-model items raised; one of them (a zero-width-only anchor
      dropping the whole headline block) is a NEW failure mode created by
      the round-1 fix.
  - date: 2026-08-05
    verdict: CLEAN
    base: 749f4194c99931adda9437e44dc6ffbbb1349159
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-766-2026-08-05-r3.md
    out_of_model_count: 2
    note: |
      Round 3, covering the two changes that landed AFTER round 2's CLEAN —
      the derive lines[0].isEmpty() branch and the security.md
      ingest-vs-presentation clarification. Both were new unaudited surface
      in a security-relevant path, so the round-2 CLEAN was not sufficient to
      merge on. CLEAN. Two out-of-model items, both advisory: the mirror case
      (an ORIGINAL that canonicalizes away, leaving an anchored block with an
      empty originalLine) which the auditor verified harmless on all four
      consumers since subordinateFor short-circuits on empty; and a note that
      the new branch's premise holds because no sanitizer pass can empty a
      line or delete the renderer-authored newline, which no test currently
      pins.
clarity_check:
  date: 2026-08-05
  verdict: PASS
  warnings:
    - >-
      Self-check round 1 found a wrong premise ("render-side switch only",
      5 files): the anchor-aware entry point needs a reader language
      neither degraded surface receives. Resolved by user decision via
      refine commit 749f4194 (files_budget 5 -> 16). Re-linted PASS.
    - >-
      Census re-run live at start: `DisplayHeadline.of(` returns exactly
      the 2 IN sites (DegradedDigestRenderer:78, SummaryProseGenerator:233)
      plus the 1 OUT site (CategoryRollupGenerator:329). The three sites
      the table marks ALREADY SWITCHED no longer match — confirmed on
      `anchorFirst`. No unlisted caller.
  blockers: []
escalation_reason:
---

# M1-766: Anchor-first degraded renderers

## Context

Filed out of M1-759 (2026-08-04). That ticket makes the English anchor a
display artifact, but deliberately introduces a NEW `DisplayHeadline`
entry point rather than changing `of(Post, …)` in place, because
`of(Post, …)` has four callers and two of them —
`DegradedDigestRenderer.render` (line 78) and
`SummaryProseGenerator.degradedProseFor` (line 233) — are outside its
`files_scope`. Changing the shared method would have altered two
user-facing surfaces with no authorized test. So M1-759 leaves them
rendering the original, and this ticket closes the gap.

## Why the degraded path deserves the anchor most

The degraded renderers run when the LLM is unavailable, over budget, or
has refused. That is exactly the condition under which display-time
translation is impossible — and exactly the condition under which a
pre-computed column costs nothing. A reader who gets a degraded digest
today sees raw foreign headlines with no recourse; after this ticket they
see the English anchor that was already computed at ingest, with the
publisher's own words bracketed beneath.

M1-759 already added the anchor to `EligiblePostQuery.Post` and to all
three projections that populate it, and both surfaces here take that
record — so no SQL and no new projection is needed.

**What the filing pass missed** (found at `start`, 2026-08-05; scope
widened 5 → 16 files by user decision). The anchor-aware entry point is
not a drop-in for `DisplayHeadline.of`: `usesAnchor(headline,
sourceLanguage, scopeLanguage)` and `primaryFor(primary,
primaryInReaderLanguage)` both need the READER's language, and neither
degraded surface receives one — `render(List<Post>)` has no language
parameter and `degradedProseFor` is `static` with none either. Without
it the bracket invariant cannot be honoured: promoting the anchor
unconditionally shows a Czech reader English for a Czech-source post
(exactly what `usesAnchor` exists to suppress), and bracketing
unconditionally brackets every English headline in an English digest,
which inverts D29 (c)'s "unbracketed means already in your language".
So both signatures grow a `scopeLanguage` parameter and the six call
sites across four caller files pass the value they already hold. That
threading, not the anchor switch, is the bulk of the diff.

## Census

The class is "callers of `DisplayHeadline.of(…)`". Re-run at `start` —
M1-759 lands first and will already have moved several of these to its
new anchor-aware entry point, so the table below is the pre-M1-759
picture and the disposition is what must be TRUE when this ticket
finishes:

```
grep -rn "DisplayHeadline\.of(" --include=*.java infochat-provider/src/main
```

| Site | Disposition |
|---|---|
| `digest/DegradedDigestRenderer.java:78` | IN — switch to the anchor-aware entry point |
| `summary/SummaryProseGenerator.java:233` (`degradedProseFor`) | IN — switch to the anchor-aware entry point |
| `digest/DigestRenderer.java:865` | ALREADY SWITCHED by M1-759 — verify, do not re-edit |
| `command/ClusterBlockRenderer.java:116` | ALREADY SWITCHED by M1-759 — verify, do not re-edit |
| `command/SavedCommandHandler.java:413` | ALREADY HANDLED by M1-759 via the `(String, String, sanitizer)` overload; the anchor itself arrives with M1-765 |
| `digest/CategoryRollupGenerator.java:329` | OUT — prompt builder, keeps the original-only overload (M1-747) |

If the re-run finds a caller not in this table, that is a new site added
between filing and start: stop and surface it rather than deciding its
disposition silently.

## Notes

- `blocked_by: M1-759` is a real dependency: the entry point, the bracket
  invariant and the anchor-absent branch this ticket adopts are all
  introduced there.
- The companion follow-up from the same M1-759 pass is M1-765 (the
  `saved_post` anchor snapshot). The two are independent and share no
  files, so they may run in parallel once M1-759 lands.
