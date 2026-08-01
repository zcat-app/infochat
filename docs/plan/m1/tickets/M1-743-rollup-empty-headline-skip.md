---
id: M1-743
title: "Roll-up must not fabricate over an empty headline set"
status: pending
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
remediates: M1-728
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - docs/design/03-commands.md
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Re-enabling the body fallback for the roll-up prompt (passing a real
    body to `DisplayHeadline.of`). M1-728 feeds the roll-up TITLES only by
    design — the roll-up is told not to reproduce item detail — and a
    Bluesky-only category degrading to no-prefix is this ticket's chosen
    shape, not body-snippet synthesis. A diff that puts body text back
    into the roll-up prompt has left scope.
  - >-
    The roll-up's failure and degrade rendering. A no-roll-up outcome
    already ships the category with header (+ headlines) + footer and no
    synthesis line, and degrades to deterministic headlines on the
    `--short` path (`DigestRenderer.java`, unchanged). This ticket adds
    one more producer of that EXISTING outcome; it changes no render
    path.
  - >-
    The M1-728 prompt mechanics: sentence bands, the char-budget drop,
    the thread/filler/no-quantity instructions, and the D21 delimiter
    shape. All unchanged.
  - any other module
acceptance:
  - >-
    `CategoryRollupGeneratorTest.allTitlelessSectionSkipsTheLlmCall`
    passes: a section whose posts are ALL titleless (the Bluesky/Nostr
    shape — blank titles and `untitled` sentinels resolve to no headline
    via `DisplayHeadline.of(title, null, ...)`) produces ZERO LLM calls
    (stub call count stays 0) and `Optional.empty()` — the category
    ships without a prefix instead of delivering a fabricated synthesis.
  - >-
    `CategoryRollupGeneratorTest.allClustersDroppedSkipsTheLlmCall`
    passes: a char budget small enough that every cluster drops
    likewise produces ZERO LLM calls and `Optional.empty()`.
  - >-
    The skip is logged at INFO with the section tag and the reason
    (empty headline set), so a missing roll-up is as explainable as the
    M1-728 budget drop. One of the tests above pins the log's level,
    tag, and reason via the captured-handler pattern already in the
    test class.
  - >-
    A section emitting at least one headline line is unchanged: exactly
    one LLM call per category (the pre-existing
    `producesOneRollupPerCategory` test pins this).
  - >-
    The M1-728 roll-up paragraphs in `docs/design/03-commands.md` §3.12
    and `docs/design/05-llm-and-embeddings.md` §5.4.5 state the
    empty-input skip.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
      — all-titleless section skips the LLM call; all-clusters-dropped
      section skips the LLM call; the skip logs section tag + reason at
      INFO.
  preserves:
    - >-
      Every existing `CategoryRollupGeneratorTest` assertion — titles
      only (no body, no URL), DisplayHeadline truncation, sentence-band
      boundaries, thread/filler/no-quantity instructions, the
      partial-budget drop + log, the D21 UUID-delimiter shape, and the
      sanitizer/translation output chain. The skip changes only the
      zero-line case; `buildPrompt`'s return type may move to
      `Optional<String>` to carry it, with existing call sites updated
      mechanically and every assertion preserved.
    - >-
      `DigestRendererTest` / `DigestRendererSectionsTest` /
      `SummaryCommandHandlerTest` / `RetryCommandHandlerTest` roll-up
      call-count and failure-path assertions.
    - all tests currently green on main
spec_refs:
  - docs/design/03-commands.md §3.12
  - docs/design/05-llm-and-embeddings.md §5.4.5
decision_refs:
  - D17
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-743: Roll-up must not fabricate over an empty headline set

## Context

M1-728 made the category roll-up prompt titles-only: each post
contributes a line via `DisplayHeadline.of(p.title(), null, sanitizer)`,
with a NULL body so the helper's body fallback stays off and the roll-up
never reproduces item detail. A post with no usable title — the
Bluesky/Nostr shape (all 729 corpus Bluesky titles are empty; blank
titles and the `untitled` sentinel resolve to the empty string) —
contributes NO line.

When EVERY post in a section is titleless, or the char budget drops
every cluster, the prompt's untrusted block is therefore EMPTY — and
`generateRollup` still calls the LLM, which is asked to "name the
themes" of nothing. The model fabricates a synthesis, and the
fabrication is sanitized, translated, and delivered as a factual roll-up
prefix in a push message the reader cannot check. Pre-M1-728 the same
category carried bodies, so the roll-up had material; this is a
regression the M1-728 redteam flagged out-of-model (the threat model
does not commit to roll-up faithfulness) with exactly this fix as the
recommendation.

The fix is the roll-up's own failure-containment shape: when no
headline lines were emitted, skip the LLM call and yield
`Optional.empty()` — the category ships without a prefix, the D17-flavored
degrade the render paths already handle. A fabricated roll-up is worse
than none: none renders deterministic headlines the reader can verify.

## Acceptance

As frontmatter: two named tests (all-titleless skip, all-dropped skip),
the INFO skip-log with section tag + reason, the non-empty path
unchanged at one LLM call per category, both design-doc paragraphs
stating the skip, and a green `mvn verify`.

## Out-of-scope

No body fallback in the roll-up prompt (M1-728's titles-only design
stands — the fix is to skip, not to synthesize from snippets). No render
path changes: `DigestRenderer`'s no-roll-up and degraded handling already
covers the outcome this ticket produces more of. No changes to the
M1-728 prompt mechanics (bands, budget drop, instructions, D21 shape).

## Notes

- The zero-line case subsumes the degenerate inputs: an empty
  `categoryClusters` list and an `untitled`-only section both emit zero
  lines and take the same skip path — no special-casing needed beyond
  the line count.
- Implementation pointer: `buildPrompt` already counts emitted lines
  (its `n` counter); returning `Optional<String>` (empty at zero lines)
  lets `generateRollup` skip the provider call before
  `llmRouter.forTask` runs. The skip log should mirror the M1-728
  budget-drop log (`section {}`, `"other"` for the null tag).
- Pre-flight: `python3 scripts/lint-ticket.py
  docs/plan/m1/tickets/M1-743-rollup-empty-headline-skip.md` is clean.
