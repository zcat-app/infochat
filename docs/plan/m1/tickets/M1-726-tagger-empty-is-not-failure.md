---
id: M1-726
title: "Tagger treats a correct \"no topic fits\" as a failure and relabels the post with the source's topic tags"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerIT.java
  - docs/spec/llm.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: high
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The bootstrap-tags fallback itself. It stays exactly as it is for
    the failure modes it was designed for — `SCHEMA_VIOLATING`,
    `UNREACHABLE`, and a reply whose proposed tags all fail vocabulary
    validation. This ticket narrows WHEN it fires, not what it does.
  - >-
    `source.bootstrap_tags`, `/add-source --tags`, and the rule that
    every source carries at least one tag. Unchanged — the fallback
    still needs them.
  - >-
    The `personal` / off-topic classifier label and any digest demotion
    built on it. That is a separate concern: this ticket handles posts
    where NO vocabulary tag fits, not posts that legitimately tag
    on-topic but are personal in kind.
  - >-
    `docs/spec/security.md:1359` ("Tagger failure → fall back to
    `source.bootstrap_tags`, mark the post, throttled admin notify").
    That sentence stays true — this ticket changes what counts as a
    tagger failure in `llm.md`, not what happens on one. A diff that
    edits security.md has left scope.
  - >-
    The partial-valid rule (`spec/llm.md:410-416`): when SOME proposed
    tags pass validation the valid ones are kept and the invalid ones
    silently dropped. Unchanged and explicitly still correct.
  - >-
    `MAX_TAGS_PER_POST` capping and `ValidationResult.cappedCount`. A
    reply capped down to a non-empty set is already a SUCCESS and is
    not touched.
  - >-
    The retry-backoff policy (`retryBackoff.sleepBeforeRetry()` on the
    UNREACHABLE path, M1-221). Unchanged.
  - any other module
acceptance:
  - >-
    A first-attempt reply that parses cleanly and proposes NO tags at
    all — `{"tags": []}`, the exact output `prompts/tagger.md` instructs
    the model to emit when nothing fits — is an outcome of its own,
    NOT a failure. The post is written with `tags = '{}'`,
    `tagger_done = TRUE`, `tagger_fallback = FALSE`. No retry, no
    bootstrap fallback, no admin notification.
  - >-
    A reply that proposes tags of which NONE pass vocabulary validation
    keeps today's behaviour exactly: retry once with the same primary
    prompt, then bootstrap fallback with `tagger_fallback = TRUE` and
    the throttled admin notification. The two cases are distinguished by
    `ValidationResult.invalidCount()`, which the worker already computes
    and logs one line above the branch
    (`TaggerWorker.java:359` vs `:362`) — `invalidCount == 0` means the
    model proposed nothing, `invalidCount > 0` means it proposed only
    garbage.
  - >-
    The distinction survives the SECOND attempt too. A first attempt
    that is `SCHEMA_VIOLATING` or `UNREACHABLE`, retried, whose retry
    returns a clean empty list, resolves to the same no-tags outcome
    rather than falling through to bootstrap. A test pins
    `UNREACHABLE` → clean `{"tags": []}` → `tags = '{}'`,
    `tagger_fallback = FALSE`.
  - >-
    The atomic single-statement write is preserved: `tags`,
    `tagger_done` and `tagger_fallback` still move in ONE `UPDATE`
    (`TaggerWorker.java:499`). M1-034a §Notes calls splitting it
    non-negotiable — a crash window with `tagger_done = TRUE` and
    unwritten `tags` is exactly the state this ticket makes reachable
    more often, since the empty-tags path is now a normal outcome
    rather than a rare failure.
  - >-
    A post with `tags = '{}'` matches no digest or `/summary` tag
    predicate and therefore does not appear in either. An IT pins this
    end to end: a post tagged empty by the worker is absent from a
    group digest whose subscribed source carries `bootstrap_tags` that
    would previously have been copied onto it. This is the user-visible
    point of the ticket and the assertion that would catch a fix that
    changes the worker but leaves the post reachable.
  - >-
    `spec/llm.md` §Failure handling is amended to distinguish the two
    cases. It currently says the bootstrap fallback "fires only when
    **zero** valid tags survive validation", which contradicts
    `spec/llm.md:70`'s own contract that the tagger "produces a list of
    **zero-or-more** controlled-vocabulary tags". The amendment makes
    zero-proposed a legitimate output per line 70 and reserves the
    fallback for zero-**surviving**-from-a-non-empty-proposal.
  - >-
    `docs/design/05-llm-and-embeddings.md` §5.4.2 records the same
    split in the tagger's failure-path description.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
      — a clean `{"tags": []}` yields `tags = '{}'`,
      `tagger_fallback = FALSE`, ZERO retries and ZERO admin
      notifications; a reply proposing only out-of-vocabulary tags still
      retries once and then falls back to bootstrap with
      `tagger_fallback = TRUE`; the two cases are asserted side by side
      in one test class so the distinction cannot be silently collapsed
      again; `UNREACHABLE` then clean-empty resolves to no-tags, not
      bootstrap; `SCHEMA_VIOLATING` then clean-empty likewise.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerIT.java
      — a post the tagger empties is absent from a group digest and from
      `/summary` for a scope subscribed to the source whose
      `bootstrap_tags` would previously have been copied onto it.
  preserves:
    - >-
      Every existing `TaggerWorkerTest` assertion for the
      `SCHEMA_VIOLATING` (different fallback prompt),
      `ZERO_VALID`-from-invalid-proposals (same prompt) and
      `UNREACHABLE` (same prompt, after backoff) retry SHAPES. Only the
      clean-empty case changes; the per-path retry choice
      (`TaggerWorker.java:276-296`) is untouched.
    - >-
      The partial-valid assertions — a reply mixing valid and invalid
      tags still keeps the valid ones and drops the invalid silently.
    - >-
      `TaggerWorkerBackoffTest` (M1-221) and `TaggerWorkerClockIT` in
      full.
    - >-
      `TagVocabularyRefreshTest`.
    - >-
      The atomic-write assertion on the single `UPDATE`.
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling
  - docs/spec/llm.md §SPI shape
decision_refs:
  - D19
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-726: a correct "no topic fits" is punished with the source's topic tags

## Context

`prompts/tagger.md` tells the model, in its own rule list
(`05-llm-and-embeddings.md:307`):

> - If none fit well, output `{"tags": []}`.

`TaggerWorker` then treats compliance as a failure:

```java
// TaggerWorker.java:362
if (validated.valid().isEmpty()) {
    return AttemptResult.zeroValid();
}
```

`ZERO_VALID` retries **the same prompt** (`:281-285`, on the reasoning
that a vocabulary mismatch is a content issue and a different prompt
shape will not help). A correct model returns `{"tags": []}` again. The
second failure lands on `:318`:

```java
return new TaggerOutcome(Outcome.BOOTSTRAP, row.bootstrapTags());
```

So a post the tagger correctly judged to have no topic is written with
the **source's** topic tags. A birthday photo from a security account is
stored tagged `security`. Since a group's `tag_mode = 'ALL'` digest query
is the union of its subscribed sources' `bootstrap_tags`
(`commands.md` §Periodic group digests), that post then matches by
construction and renders under a topic header in the group digest.

Two LLM calls are spent per off-topic post to arrive at the wrong answer,
and each one lights `throttledAdminNotifier.notifyOnce(...)` at `:313`.
The notification is coalesced on `error_class`, so this is not a flood —
it is worse: the tagger-fallback alarm is permanently on during normal
operation, so it carries no information when a real tagger regression
happens.

## The spec contradicts itself

| Location | Says |
|---|---|
| `spec/llm.md:70` | "Tagger — produces a list of **zero-or-more** controlled-vocabulary tags" |
| `spec/llm.md:418` | "The bootstrap-tags fallback fires only when **zero** valid tags survive validation" |

Line 70 makes an empty list a legitimate output of the stage. Line 418
makes it a failure. The implementation follows line 418.

M1-034a's acceptance text describes only the genuine failure —
*"the JSON parsed but ZERO entries passed vocabulary validation"* — which
is the case where the model **proposed** tags and none survived. The
implementation widened it to cover a proposal of nothing at all. That
widening is the defect.

## The distinguishing datum already exists

`ValidationResult` carries `invalidCount`, and the worker logs it one
line above the branch that ignores it (`:357-360`):

| Model returned | `valid` | `invalidCount` | Correct disposition |
|---|---|---|---|
| `{"tags": ["blockchain","crypto"]}`, neither in vocabulary | empty | > 0 | failure — retry, then bootstrap |
| `{"tags": []}` | empty | 0 | success with no tags |

So the fix is a condition, not a redesign, and the safety net keeps
serving the case it was built for: small models emitting garbage.

## Why `risk: high` on a small diff

The bootstrap fallback is the mechanism that guarantees every post
carries at least one tag, which is why `/add-source` requires `--tags` at
all. Narrowing it means some posts now legitimately carry none — a state
the rest of the pipeline must tolerate. The empty-tags path stops being
a rare failure and becomes a normal outcome, so the atomic
`tags + tagger_done + tagger_fallback` write and every downstream tag
predicate get exercised with `'{}'` far more often than before.

## Notes

Posts written with `tags = '{}'` are unreachable by any tag-keyed
retrieval — digest and `/summary` both. That is inherent to a tag-keyed
model and is the intended outcome here, not a gap: a post with no topic
is not retrievable *by topic*. Making such posts separately auditable
would need a column, and `tagger_fallback` already provides the audit
trail for the other branch. Not filed.
