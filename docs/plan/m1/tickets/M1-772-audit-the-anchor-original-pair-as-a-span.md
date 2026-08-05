---
id: M1-772
title: "Audit the anchor/original pair as a span"
status: pending
created: 2026-08-05
last_updated: 2026-08-05
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/render/DisplayHeadline.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    WIDENING THE OUTPUT SANITIZE UNIT. The two rendered lines keep their
    two independent `sanitize` calls, one author's field each (M1-697).
    The new pass is DETECTION-ONLY: it computes matches and emits audit
    rows, and its rewritten output is discarded. Merging the two calls
    into one over a concatenation is the content-suppression vector the
    categorized-render redteam caught and is exactly what must not
    happen.
  - >-
    THE BRACKET / RENDER LAYOUT. `anchorBlock`, `primaryFor`,
    `subordinateFor`, `bracketed` and the D29 (c) bracket invariant are
    untouched. Nothing about what the reader SEES changes; only whether
    an audit row is written.
  - >-
    THE CLOSED LIST ITSELF. No entry is added, removed or reworded.
    `LlmOutputSanitizerCore.CLOSED_LIST` and its patterns are inputs
    here.
  - >-
    THE INGEST LEG. Whether `title_en` is genuinely English is M1-771's
    subject and is independent of this ticket.
  - >-
    CROSS-ROW OR WHOLE-MESSAGE SPANS. The unit is ONE post's
    (original, anchor) pair. Widening it to two rows of a `/saved` page,
    or to a whole digest, would re-introduce the multi-author span the
    spec rejects: a co-clustered attacker could then delete a third
    party's post.
acceptance:
  - >-
    SPAN DETECTION EXISTS. `LlmOutputSanitizer` gains a detection-only
    entry point that takes the two rendered lines of one post and emits
    `LLM_OUTPUT_SANITIZED` audit rows for closed-list entries that match
    across the pair. The primitives already exist —
    `applyClosedListStripWithMatches(String)` returns
    `(rewritten, matches)` and `emitAuditRows(List<String>)` is already
    separate — so this composes them; it does not reimplement matching.
  - >-
    NO DOUBLE COUNTING. An entry matching wholly WITHIN one line is
    already redacted and audited by that line's own `sanitize` call. The
    span pass must report only matches present in the joined form and
    absent from both per-field results, so a single-field hit produces
    exactly one audit row, not two. This is the fiddly part of the
    ticket and the reason it is not a two-line change.
  - >-
    THE JOIN CANNOT FABRICATE A MATCH. The two lines are joined for
    DETECTION with a separator that mirrors what the reader receives —
    the renderer emits `primary + "\n" + subordinate` — so the pass sees
    what a copy-paste would produce. It must not concatenate without a
    separator, which would splice the tail of one line and the head of
    the next into a token neither author wrote (the same argument
    `flattenToOneLine` makes for replacing whitespace runs rather than
    deleting them).
  - >-
    WIRED WHERE BOTH VALUES EXIST. `DisplayHeadline.derive` holds the
    original and the anchor, and is the single funnel for all THREE
    anchor-bearing call sites (see §Census), so wiring it there covers
    the scheduled digest, `/summary` and `/saved` at once. The pass runs
    only when an anchor is actually present — a null-anchor row renders
    one line and has nothing to span, as do the three `DisplayHeadline
    .of` surfaces, which are out of this ticket's reach by construction.
  - >-
    SPEC SAYS COVERED, NOT ACCEPTED. `docs/spec/security.md` §"Flag
    position mirrors the parser's own scan" currently accepts a residual
    for a command "split ACROSS two posts' fields". With this ticket the
    one-post original/anchor pair is COVERED, so the section states that
    and the sanitize-call census names the anchor call. The two-posts
    case remains an accepted residual on its own reasoning (a joint pass
    there would swallow other publishers' bytes); this ticket must not
    blur the two.
  - >-
    NAMED TESTS. `LlmOutputSanitizerTest` pins: a flag-bearing entry
    split across the pair emits exactly one audit row; the same entry
    wholly within one line still emits exactly one (not two); a pair
    with no closed-list content emits none and opens no DB connection.
    `DisplayHeadlineTest` pins that the RENDERED bytes are unchanged by
    the new pass — detection must not alter output.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm/LlmOutputSanitizerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/render/DisplayHeadlineTest.java
  preserves:
    - >-
      M1-697's one-author's-field OUTPUT sanitize unit — the two
      rendered lines keep two independent calls.
    - >-
      The flatten → sanitize → truncate order and the
      BODY_SCAN_LIMIT pre-bound on both operands.
    - >-
      D29 (c)'s bracket invariant and the anchor-first render shape
      (M1-759, M1-765).
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D29
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-772: Audit the anchor/original pair as a span

## Context

Filed 2026-08-05 from M1-765's round-2 red-team audit
(`docs/plan/m1/redteam/M1-765-2026-08-05-r2.md`, one low/AUDIT-EVASION
finding).

Since M1-759 the render surfaces put a post's ORIGINAL and its English
ANCHOR on adjacent lines: the anchor in the primary slot, the
publisher's own words bracketed beneath. `DisplayHeadline.derive`
sanitizes each line independently — correctly, because M1-697 requires
one author's field per `sanitize` call.

The consequence is that a flag-bearing closed-list entry **split across
the two lines** matches neither call. `/list-sources --all` is one
closed-list entry; bare `/list-sources` is not an entry at all (listing
sources is unprivileged), and `--all` alone is not either. So a pair
with the command word on one line and the flag on the other produces no
redaction, no `[redacted command]`, and — the part that matters here —
**no `LLM_OUTPUT_SANITIZED` audit row**. An operator triaging
`audit_log` sees nothing while the delivered message carries a
copy-pasteable admin-only command.

Two lines is not a bound. `docs/spec/security.md` §"Flag position
mirrors the parser's own scan" establishes that the argument run spans
the **whole message, across newlines** — the router hands the handler
the entire body and `ListSourcesArgs.parse` tokenizes it with
`split("\s+")`. And `DisplayHeadline.bracketed` wraps only the line's
ends, so `[… --all …]` still yields `--all` as a clean token.

## Why this is closable, and why the spec's residual does not cover it

The spec accepts the analogous split across **two posts' fields**, and
its reasoning is that the alternative is worse: one sanitize call over
assembled multi-author prose lets a span deletion swallow *other
publishers'* bytes, so a co-clustered attacker could delete a third
party's post.

That reasoning does not transfer to this pair. Both values come from
ONE post — a field and that same field's anchor
(`DisplayHeadline.anchorFirst` selects the field from the ORIGINAL,
then takes that field's anchor). A detection unit spanning them can
only ever reach that publisher's own bytes: self-suppression, not
third-party suppression. There is no content-suppression cost to pay,
so the case is closable rather than acceptable — and closing it is
cheaper than documenting it.

Nothing beyond v1.0 is deployed, so there is no compatibility reason to
prefer an accepted residual either.

## Census

The class is **render call sites that produce a two-line
original+anchor block**, i.e. callers of `DisplayHeadline.anchorFirst`.
Only those can carry a cross-line split; the single-line
`DisplayHeadline.of` path has no pair. Re-runnable:

```
grep -rn "anchorFirst(" --include=*.java infochat-*/src/main | grep -v render/DisplayHeadline.java
grep -rn "DisplayHeadline\.of("  --include=*.java infochat-*/src/main
```

| Call site | Path | Disposition |
|---|---|---|
| `DigestRenderer:932` | scheduled digest | COVERED — funnels through `derive` |
| `ClusterBlockRenderer:119` | `/summary`, `/retry` | COVERED — funnels through `derive` |
| `SavedCommandHandler:442` | `/saved` | COVERED — funnels through `derive` |
| `SummaryProseGenerator:233` | degraded prose | N/A — `of()`, one line, no pair |
| `DegradedDigestRenderer:78` | degraded digest | N/A — `of()`, one line, no pair |
| `CategoryRollupGenerator:329` | roll-up prompt input | N/A — `of()`, and not user-facing output |

Three anchor-bearing sites, all reaching `derive`, which is why one
wiring point covers the class. An earlier draft of this ticket said
"all four render surfaces" from memory — the grep says three carry an
anchor and three do not carry a pair at all. If a FOURTH `anchorFirst`
caller appears before this ticket runs, it inherits the fix
automatically, but re-run the grep to confirm it funnels through
`derive` rather than calling `renderLine` itself.

## Shape

Detection and rewriting are already separable in the sanitizer:

- `applyClosedListStripWithMatches(String)` → `(rewritten, matches)`
- `emitAuditRows(List<String>)` is its own step

So the span pass computes matches over the joined pair, subtracts the
matches each per-field call already reported, and emits audit rows for
the remainder. The `rewritten` form is discarded — output still comes
from the two per-field calls, untouched.

## Notes

- The audit's other three out-of-model items are recorded on M1-765 and
  are NOT this ticket: attribution of the mechanism to M1-759, the
  precondition being weaker than "one hostile publisher" (a hostile LLM
  endpoint authors the anchor outright under §Trust boundaries item 9),
  and the census still omitting two pre-existing one-field `/saved`
  calls (the `<tag>` filter echo and the display-hit sanitizer-2 leg).
  Whoever does the §Flag position edit here should fold those in while
  the section is open — they are the same paragraphs.
- M1-765 briefly carried a spec amendment describing this as an
  accepted residual; it was reverted when this closure was chosen. Do
  not resurrect that text.
