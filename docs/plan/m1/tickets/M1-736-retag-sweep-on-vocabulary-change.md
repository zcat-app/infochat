---
id: M1-736
title: "Posts tagged '{}' are never re-evaluated when the vocabulary or model improves"
status: done
created: 2026-07-31
last_updated: 2026-07-31
blocked_by: [M1-726]
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/**
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/**
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/**
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/**
  - docs/design/05-llm-and-embeddings.md
  - infochat-core/src/main/resources/db/migration/**
  - infochat-collector/src/test/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    Re-tagging posts that HAVE tags. Vocabulary drift on tagged posts
    (a tag renamed, split, or pruned) is a separate concern with
    separate blast radius; this ticket sweeps only `tags='{}'` rows —
    the M1-726 no-tags outcome.
  - >-
    Rows with `tagger_fallback = TRUE`. They carry their source's
    bootstrap tags by design; sweeping them would relitigate the
    failure path M1-726 leaves intact.
  - >-
    The live pickup path (`tagger_done = FALSE` rows waiting for their
    first tagger pass). Its scheduling, concurrency and backoff are
    unchanged — the sweep is a separate, lower-priority consumer of
    the same worker logic.
  - >-
    The no-tags rate alarm (M1-735), the `personal` label (M1-727),
    and any provider-side or command-surface change.
acceptance:
  - >-
    A sweep re-runs the tagger over posts with `tags = '{}'` AND
    `tagger_done = TRUE` — the M1-726 no-tags outcome — that have not
    yet been swept for the CURRENT tagger generation. Re-evaluated
    posts resolve through the normal chain: tags found → written by
    the same atomic single-statement UPDATE M1-034a mandates; still
    nothing → stays `tags='{}'`; failures take the normal failure
    path.
  - >-
    The generation marker bumps when the tagger's INPUTS change — a
    controlled-vocabulary change via the refresh path
    (`TagVocabularyRefresh`) and, where the configured tagger model is
    cheaply identifiable, a model change — and is persistent across
    restarts. A post already swept at the current generation is NOT
    re-tried: a test sweeps twice with unchanged inputs and asserts
    the second sweep makes zero LLM calls for the already-swept post.
  - >-
    LLM spend is bounded twice: a per-post attempt cap (a post is
    re-evaluated at most K times across all generations, then left
    alone) and a per-sweep batch cap. Both caps are pinned by test —
    a post at the attempt cap is skipped even when the generation
    bumps.
  - >-
    The sweep cannot starve the live pipeline: first-pass pickup
    (`tagger_done = FALSE`) always wins over sweep work. Pinned by a
    test that seeds both kinds and asserts processing order.
  - >-
    `docs/design/05-llm-and-embeddings.md` §5.4.2 records the sweep:
    what triggers a generation bump, the caps, and the ordering rule.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/**
      (one new class alongside TaggerWorkerTest, name at the
      implementer's discretion) pinning: a swept post whose
      vocabulary gained a fitting tag gains the tag on sweep; a post
      swept at the current generation is not re-tried; the attempt
      cap and batch cap hold; `tagger_fallback = TRUE` rows are never
      swept; first-pass pickup is processed before sweep work.
  preserves:
    - >-
      Every existing tagger assertion, including the M1-726 contract
      that a first-pass `{"tags":[]}` is terminal with no retry.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling
decision_refs:
  - D19
reviews:
  - round: 1
    date: 2026-07-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 897
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-31
  verdict: PASS
  warnings:
    - >-
      budget-breach refine applied at start (ticket-body pre-authorized):
      migration_touch flipped to true and the Flyway migration dir added to
      files_scope — no existing storage fits the generation marker or the
      per-post swept-generation/attempt-count bookkeeping.
  blockers: []
---

# M1-736: posts tagged '{}' are never re-evaluated when the vocabulary or model improves

## Context

M1-726 makes `tags='{}'` a normal terminal state — the correct answer
for genuinely untaggable content. But "untaggable" is a function of
two inputs that both change over time: the controlled vocabulary (a
new tag appears that would have fit — `TagVocabularyRefresh` exists
precisely because vocabularies grow) and the tagger model (an operator
swap or upgrade). A post judged untaggable last month may be cleanly
taggable today, and today nothing ever looks at it again:
`tagger_done = TRUE` excludes it from pickup permanently.

The fix is a bounded re-evaluation sweep, not a pending-state loop:
`tags='{}'` stays the correct TERMINAL answer for the inputs that
produced it, and the sweep only re-asks the question when the inputs
change (a generation bump), with hard caps so it can never become a
silent LLM-burning loop. Surfaced during the M1-726 escalation
discussion (2026-07-31); filed separately because it is a feature, not
failure-handling.

## Acceptance

See the `acceptance:` frontmatter — sweep eligibility and normal-chain
resolution, the persistent generation marker, the two spend caps, the
live-pipeline-first ordering, and the design-note record.

## Out-of-scope

Tagged posts (vocabulary drift on them is a different ticket's
problem), `tagger_fallback = TRUE` rows, the live pickup path's
scheduling, the M1-735 rate alarm, and the M1-727 `personal` label. If
the generation marker or the attempt cap needs a new column or table —
likely — flip `migration_touch: true` and add the migration to
`files_scope` via `escalate → refine` at start; the ticket is filed
with `migration_touch: false` because a design reusing existing
cursor/state storage is preferred if one fits.

## Notes

- The sweep reuses `TaggerWorker`'s chain rather than duplicating it:
  same prompt, same validation, same atomic write, same failure paths.
  What is new is SELECTION (which rows get another pass) and
  BOOKKEEPING (generation marker, attempt count), not tagging logic.
- A generation-bump trigger on model change may not be cheaply
  detectable (the configured endpoint/model string is config, but an
  operator can swap what answers behind the same URL). The vocabulary
  bump is the committed trigger; the model bump is "where cheaply
  identifiable" on purpose — do not build endpoint fingerprinting for
  this.
- Adjacent code: `TaggerWorker`, `TagVocabularyRefresh`, the M1-221
  `RetryBackoff` pattern for how the collector paces LLM work, and
  `ReEvaluationJob` (`collector/eval/reeval/`) — an existing
  bounded re-evaluation consumer whose scheduling shape is the closest
  precedent for the sweep.
