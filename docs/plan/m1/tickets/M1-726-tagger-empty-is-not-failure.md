---
id: M1-726
title: "Tagger treats a correct \"no topic fits\" as a failure and relabels the post with the source's topic tags"
status: deferred
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 7
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/UntaggedPostRetrievalIT.java
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
  - >-
    Any module other than `infochat-collector`, with ONE exception: the
    new `UntaggedPostRetrievalIT` under `infochat-provider`'s TEST tree.
    Acceptance item 5's retrieval and Other-bucket assertions read
    `EligiblePostQuery`, `DigestPostCollector` and `DigestCategorizer`,
    all of which live in `infochat-provider`, while
    `infochat-collector`'s pom depends on core, ssrf and llm-adapter
    only — so a collector-side test cannot reach them and no collector
    test imports `app.zcat.infochat.provider` today. No provider MAIN
    source is touched. The IT must be a NEW file so the provider-side
    diff stays purely additive while M1-722 / M1-724 / M1-727 own the
    digest test surface.
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
    A post with `tags = '{}'` stops being retrievable BY TOPIC while
    remaining retrievable in the untagged-query case. Precisely, per the
    four retrieval branches that exist today: it is EXCLUDED by a
    positional `/summary <tag>` (`p.tags @> ARRAY[?]`), by the top-3
    restriction that fires above 5 followed tags (`p.tags && ?`), and by
    `tag_mode='EXPLICIT'` in both the digest and `/summary`
    (`p.tags && (SELECT ... scope_tag ...)`); it is RETRIEVED under
    `tag_mode='ALL'` with no positional tag and ≤5 followed tags, which
    applies no tag predicate at all
    (`EligiblePostQuery.java:246`, `DigestPostCollector` POSTS_ALL_SQL).
    Where it is retrieved it carries no qualifying tag and therefore
    renders in the D62 **Other** bucket, never under a topic header.
    `UntaggedPostRetrievalIT`, under `infochat-provider`'s test tree,
    pins the user-visible point: a post carrying `tags = '{}'` renders
    under Other rather than under the topic header its source's
    `bootstrap_tags` would previously have placed it under, and no
    longer matches `/summary <that-tag>`. It SEEDS that post directly —
    the tagger does not run in the Provider — so it pins the retrieval
    consequence of the empty-tags state, while the collector-side tests
    pin that the tagger is what produces that state. Splitting the
    assertion across the two modules is forced by the module graph, not
    a choice: see §out_of_scope.
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
      (existing file from M1-034a — coverage added, not created) — end
      to end through the collector pipeline: a post whose tagger reply
      is a clean `{"tags": []}` is persisted with `tags = '{}'`,
      `tagger_done = TRUE` and `tagger_fallback = FALSE`, and its
      source's `bootstrap_tags` do NOT appear on the row. Collector-side
      only: it asserts the STORED state, not what retrieval does with
      it.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/UntaggedPostRetrievalIT.java
      (new) — seeds a READY post with `tags = '{}'` whose source carries
      non-empty `bootstrap_tags`, then pins all three reachability legs
      against the real queries: it renders under the D62 Other bucket
      rather than under the topic header those `bootstrap_tags` name; it
      does NOT match `/summary <that-tag>`; and it IS still retrieved by
      a bare `/summary` in a `tag_mode='ALL'` scope with ≤5 followed
      tags — which pins that the ticket narrows topic-keyed
      reachability without making the post disappear from the corpus.
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
redteam_audits:
  - date: 2026-07-30
    verdict: FINDINGS
    base: 96217c955ca1962b7ed066b75b090a190dbc9f41
    head: working tree (uncommitted branch m1/M1-726-tagger-empty-is-not-failure)
    verdict_file: docs/plan/m1/redteam/M1-726-2026-07-30.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Round 1 at the /m1-tick run gate, ahead of review. Both findings share
      one root: parseTags drops non-textual JSON array elements before
      validation, so a wrong-SHAPE tags array is indistinguishable from a
      deliberate empty list under the invalidCount discriminator this ticket
      adopts. The medium finding is a conformance gap against security.md
      §Failure handling (schema-violating output must retry then take the
      stage failure path); the low finding is the loss of the only
      spec-committed detector for a wholly non-functioning tagger. Note that
      security.md is explicitly out_of_scope for this ticket, so the
      conformance gap cannot be closed by amending the promise.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-30
    category: AUDIT-EVASION
    severity: medium
    promise: |
      docs/spec/security.md §Failure handling — "Schema-violating LLM output
      (wrong JSON shape, unexpected label value, missing required field) is
      treated identically to an unparseable reply at every stage: retry once,
      then apply the stage-specific failure path below." and "Tagger failure →
      fall back to source.bootstrap_tags, mark the post, throttled admin
      notify."
    gap: |
      The new SUCCESS-vs-failure split keys entirely on
      ValidationResult.invalidCount(), but invalidCount cannot see a
      wrong-TYPED tags array: parseTags silently discards every non-textual
      array element BEFORE validation, so {"tags":[{"name":"ai"}]},
      {"tags":[null]} or {"tags":[1,2]} yield parsed=[] → valid=[] →
      invalidCount=0 → NO_TAGS. A schema-violating reply is therefore
      classified as the model's deliberate "nothing fits" answer and is
      neither retried nor routed to the bootstrap fallback. Pre-diff the same
      reply produced ZERO_VALID → retry → BOOTSTRAP + throttled admin notify,
      so the diff introduces the regression. The diff's own spec text asserts
      the invariant the code does not hold ("zero invalid means the model
      proposed nothing" in both llm.md and 05-llm-and-embeddings.md).
    repro: |
      A tagger endpoint answers with the well-formed-JSON but wrong-shape body
      {"tags":[{"name":"ai"}]} (a shape small local models really do emit).
      parseTags drops the non-textual element, validate reports valid=[]
      invalid=0, tryOnce returns NO_TAGS, isAnswered short-circuits on attempt
      1, and persistCursor writes tags='{}', tagger_done=TRUE,
      tagger_fallback=FALSE. No second attempt, no
      tagger.fallback_to_bootstrap WARN, no throttled admin notification.
    suggested_fix_class: input-sanitization
  - date: 2026-07-30
    category: AUDIT-EVASION
    severity: low
    promise: |
      docs/spec/security.md §Failure handling — "Tagger failure → fall back to
      source.bootstrap_tags, mark the post, throttled admin notify." combined
      with §Trust boundaries item 9 — everything a generative endpoint returns
      is endpoint-chosen input, so a hostile or compromised endpoint is in
      scope.
    gap: |
      After the diff a clean {"tags":[]} on every call is a terminal success
      for every post, so an endpoint that answers empty 100% of the time
      drives the entire corpus to tags='{}' while emitting zero operational
      signal: no tagger_fallback row marker, no WARN, no
      ThrottledAdminNotifier call. The replacement observability the diff
      cites in llm.md ("sustained high invalid rates surface an operator
      alert") cannot fire either, because the all-empty case reports N=0 AND
      M=0. The diff removes the only spec-committed detector for a wholly
      non-functioning tagger stage and adds none.
    repro: |
      The configured TAGGER endpoint degrades (or an operator model swap
      regresses) and returns {"tags":[]} to every request. Every ingested post
      is persisted with tags='{}', tagger_fallback=FALSE, one LLM call each.
      /summary <tag>, follow-tag subscriptions, the searchPosts tool and
      per-tag digest categorization all return nothing for new content, while
      the admin notification channel stays silent.
    suggested_fix_class: audit-log-coverage
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings: []
  blockers: []
deferred_on: M1-735
deferred_reason: spec-amend
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

## Where an untagged post actually goes

Worth stating exactly, because "it gets no tags" does not mean "it
disappears". Only two of the four retrieval branches carry a tag
predicate at all:

| Retrieval | Predicate | Untagged post |
|---|---|---|
| `/summary <tag>` | `p.tags @> ARRAY[?]` | excluded |
| bare `/summary`, >5 followed tags | `p.tags && ?` (top-3) | excluded |
| `tag_mode='EXPLICIT'` (digest and `/summary`) | `p.tags && (SELECT … scope_tag …)` | excluded |
| **`tag_mode='ALL'`, no positional tag, ≤5 followed** | **none** | **retrieved** |

That last row is the default, and it holds for the digest too:
`DigestPostCollector`'s `POSTS_ALL_SQL` has no tag predicate — only
`POSTS_EXPLICIT_SQL` does. So an untagged post is still collected, and
since it carries no qualifying tag it lands in the D62 **Other** bucket.

That is the right outcome and is what makes this ticket's benefit
concrete: the change is not "the cat picture vanishes", it is "the cat
picture stops being filed under SECURITY". It renders in Other, where a
reader can see it and ignore it, and it stops matching
`/summary security`.

**Doc divergence surfaced, not fixed here.**
`docs/design/03-commands.md:940` says "Digest query: `ALL` mode uses the
union of subscribed-source `bootstrap_tags`". No such predicate exists
in `POSTS_ALL_SQL`. The two readings agree today only because every post
inherits its source's tags on the fallback path — which is precisely what
this ticket stops. Correcting that sentence is a `spec:` commit against
design notes, not part of this diff.
