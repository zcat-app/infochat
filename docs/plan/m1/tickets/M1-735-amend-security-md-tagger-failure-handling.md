---
id: M1-735
title: "Amend docs/spec/security.md §Failure handling"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope:
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
acceptance:
  - >-
    `docs/spec/security.md:1361` no longer names `source.bootstrap_tags`
    as the tagger's failure disposition. The row becomes: a tagger
    failure leaves `post.tags` EMPTY, marks the post, and fires the
    throttled admin notification. The marking and the notification are
    unchanged — only the tag-guessing is removed.
  - >-
    `docs/spec/llm.md` §Failure handling's tagger recap is amended to
    match, so the two files state one rule. Today its opening clause
    (`llm.md:409`, "Tagger — bootstrap tags fallback") and its
    fallback-firing sentence (`llm.md:424`) both assert the disposition
    this amendment removes.
  - >-
    The stated justification for `/add-source --tags` being mandatory is
    corrected wherever it appears. `CLAUDE.md:75` currently reads "so
    every source has a deterministic fallback when LLM tagging fails" —
    that reason ceases to exist. `source.bootstrap_tags` and the `--tags`
    requirement both REMAIN, because the union of bootstrap tags seeds
    the controlled vocabulary; only the rationale sentence changes.
  - >-
    No code, test, or migration is touched. This ticket lands the spec
    change only; M1-726 implements against it after reopening.
test_plan:
  adds:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/llm.md §Failure handling (recap)
decision_refs:
spec_amend_for: docs/spec/security.md §Failure handling
spec_amend_parent: M1-726
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-735: Amend docs/spec/security.md §Failure handling

## Context

`docs/spec/security.md:1361` currently reads:

```
- **Tagger** failure → fall back to `source.bootstrap_tags`, mark the post,
  throttled admin notify.
```

The fallback exists so that every post carries at least one tag. Its cost
is that a post whose topic we could not determine is stored under its
**source's** topic — a personal photo from a security account is filed as
`security`, and it then matches `/summary security` and renders under that
digest header by construction.

That is a guess presented as data. The operator gains an always-tagged
corpus and loses the ability to trust any tag. This amendment removes the
guess and keeps every part of the failure handling that carries real
information: the post is still marked, the admin is still notified
(throttled), and the post itself is still stored and still retrievable
through the branches that apply no tag predicate.

The v1 answer to an LLM that cannot produce usable tags is to run a model
that can — model selection, not a per-model compensation layer in the
pipeline. Storing a fabricated topic to keep a column non-empty works
against that: it hides the failure inside data that reads as a result.

## Acceptance

See the YAML `acceptance:` list above. In prose: `security.md`'s tagger
failure row stores empty tags instead of `source.bootstrap_tags`; the
`llm.md` recap is brought into agreement; the `/add-source --tags`
rationale is restated (the flag stays — it seeds the vocabulary); no code
changes here.

## Out-of-scope

**To be filled in before `/m1-tick start M1-735`.** The linter's
OUT-OF-SCOPE-PRESENT BLOCKER will refuse the start until it is.

## Notes

- Blocks M1-726, which is `deferred` on this ticket. M1-726 holds a
  written, `mvn verify`-green implementation of the adjacent case (a
  tagger reply that cleanly proposes NO tags is an outcome, not a
  failure) on branch `m1/M1-726-tagger-empty-is-not-failure`. That work
  is unaffected by this amendment; what M1-726 gains on reopening is the
  ability to route a reply it could not read to a marked, notified,
  empty-tags failure rather than either guessing a topic or silently
  calling it an answer.
- Origin: the `/redteam` audit of M1-726 (`docs/plan/m1/redteam/M1-726-2026-07-30.md`,
  1 medium + 1 low, both AUDIT-EVASION). Both findings reduce to the
  same root — the pipeline had no way to record "we could not tag this"
  without either fabricating tags or staying silent. This amendment
  creates that third disposition.
- Adjacent, deliberately separate: M1-727 routes posts that legitimately
  carry an on-topic tag but are personal in kind to the digest's Other
  bucket via a `personal` classifier label. Different problem, same
  symptom class.
- The `tagger_fallback` column name becomes a mild misnomer once nothing
  falls back to a tag set — it marks "the tagger failed on this post".
  Renaming it is NOT part of this amendment; note it and move on.
