---
id: M1-728
title: "Category roll-up sends every post's full body and asks for one or two sentences: it neither fits a large category nor describes one"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
  - M1-714
  - M1-722
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - docs/design/07-deployment.md
  - docs/design/05-llm-and-embeddings.md
  - docs/design/03-commands.md
  - docs/plan/m1/tickets/M1-714-display-headline-for-summary-and-digest.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    `SummaryProseGenerator.buildPrompt` and its call site
    (`SummaryProseGenerator.java:179`). M1-714's rule that the SUMMARIZER
    receives the full untruncated title stands unchanged — it summarizes
    ONE cluster, where a truncated title really would have the model
    describe a fragment. This ticket changes only the roll-up, which
    sees every cluster in a category. A diff touching the summarizer
    prompt has left scope.
  - >-
    Deterministic sub-clustering of a category over `post_embedding`.
    Considered and rejected — see §Why no sub-clustering. A diff that
    adds an embedding-space grouping stage has left scope.
  - >-
    Any quantity in the roll-up output. The prompt forbids counts and a
    test pins it; the story count the reader sees comes from the
    deterministic section header, never from the model.
  - >-
    `DigestCategorizer`, category assignment, and which clusters are in
    a section. This ticket changes what the roll-up is TOLD about a
    section, not what the section contains.
  - >-
    The roll-up's failure path. A failed roll-up still degrades that
    category to deterministic headlines via
    `SummaryProseGenerator.degradedProseFor` (`DigestRenderer.java:344-357`)
    and never blocks the digest. Unchanged.
  - >-
    `infochat.digest.category-summary-enabled` and the gated
    `generateRollup` wrapper. M1-722 DELETES both and renames
    `generateRollupUnconditional` to `generateRollup`; this ticket runs
    after that and edits `buildPrompt` only. Both tickets touch
    `CategoryRollupGenerator.java`, hence `blocked_by: M1-722` — a diff
    here that re-introduces the flag, or that deletes it a second time,
    has left scope.
  - >-
    The per-call random UUID delimiter and the treat-as-untrusted
    instruction (`CategoryRollupGenerator.java:186-192`, D21). Load-bearing
    prompt-injection defense; preserved verbatim.
  - any other module
acceptance:
  - >-
    `buildPrompt` sends the post TITLE only — no body, no URL. It
    currently appends title, full body AND url for every post of every
    cluster (`:196-208`). With M1-714's measured corpus (avg RSS body
    1 037 chars) a 70-cluster category is ~150 000 characters in one
    prompt and a 328-cluster category ~90 000 tokens, bounded only by
    `infochat.summary.cluster-cap` (500 posts on `remote-llm`). A test
    asserts no body text and no URL reaches the prompt.
  - >-
    Titles are truncated to a bounded length for the roll-up prompt,
    reusing M1-714's `DisplayHeadline` helper rather than a second
    truncation rule. This REVISES M1-714's out-of-scope entry naming
    `CategoryRollupGenerator.java:199` — see §Relationship to M1-714;
    that ticket's entry is amended in place, not silently contradicted.
    A test pins a 24 000-character nitter title (the measured corpus
    maximum) reaching the prompt truncated.
  - >-
    The prompt's requested length SCALES with the category's cluster
    count instead of the fixed "one or two short sentences" at `:191`.
    Banded, deterministic, and operator-configurable via
    `infochat.digest.rollup-sentence-bands`; the shipped default is
    1 sentence up to 5 clusters, 2 up to 20, 3 up to 75, and 5 above
    that. A test pins each band boundary in both directions.
  - >-
    The prompt asks the model to name 2–4 DISTINCT threads for a
    multi-band category rather than one flat synthesis, and forbids
    filler ("various", "a number of", "several developments") in favour
    of naming concrete approaches, systems or findings. A large category
    describing itself as "various AI developments" is the failure this
    criterion exists to prevent; a test asserts the prompt carries both
    the thread instruction and the filler prohibition.
  - >-
    The prompt forbids stating any quantity. Nothing verifies a
    model-supplied count, and the digest prints the roll-up as fact in a
    push message the reader cannot check; the true count already renders
    deterministically in the section header. A test asserts the
    no-quantities rule is present in the prompt text.
  - >-
    The assembled prompt is bounded overall: when the truncated titles
    still exceed `infochat.digest.rollup-prompt-char-budget`, clusters
    are dropped from the END of the section's existing order until the
    prompt fits, and the drop is logged at INFO with the section tag and
    dropped count. Silent truncation of an LLM input is the failure mode
    that makes a bad roll-up unexplainable. A test pins the bound and
    the log.
  - >-
    Both new config keys are documented in `docs/design/07-deployment.md`
    §Configuration surface so `scripts/lint-config-keys.py` stays green.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
      — prompt contains titles only (no body, no URL); a corpus-maximum
      nitter title arrives truncated via DisplayHeadline; each
      sentence-band boundary maps to the expected requested length; the
      thread instruction, the filler prohibition and the no-quantities
      rule are all present; a section exceeding the char budget drops
      trailing clusters and logs the count; the per-call UUID delimiter
      and treat-as-untrusted instruction are unchanged.
  preserves:
    - >-
      Every existing `CategoryRollupGeneratorTest` assertion, in
      particular the D21 prompt-injection shape: the per-call random
      UUID marker, both delimiter lines, and the instruction not to
      follow embedded instructions.
    - >-
      `DigestRendererTest` / `DigestRendererSectionsTest` roll-up
      failure-path assertions — a failed roll-up still degrades that
      category to deterministic headlines and never blocks the digest.
    - >-
      `/summary --short` behaviour in `SummaryCommandHandlerTest`: it
      calls the same generator, so its LLM call COUNT is unchanged
      (one per category) even though the prompt content changes.
    - >-
      `SummaryProseGeneratorTest`'s assertion that the SUMMARIZER prompt
      still carries a long title in full (M1-714). This ticket must not
      weaken it.
    - all tests currently green on main
spec_refs:
  - docs/design/05-llm-and-embeddings.md §5.4.5
  - docs/design/03-commands.md §Periodic group digests
decision_refs:
  - D19
  - D21
  - D62
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-728: the roll-up cannot fit a large category, and cannot describe one

## Context

`CategoryRollupGenerator.buildPrompt` (`:185-210`) builds the
per-category synthesis prompt by walking every cluster in the section and,
for every post in every cluster, appending the title, the **entire body**
and the URL:

```java
for (Cluster cluster : categoryClusters) {
    for (Post p : cluster.posts()) {
        sb.append('[').append(n++).append("] ").append(p.title()).append('\n');
        if (p.body() != null && !p.body().isEmpty()) {
            sb.append(p.body()).append('\n');
        }
        ...
```

Nothing caps it. Using M1-714's measured live corpus:

| Category size | Approx. prompt |
|---|---|
| 20 clusters | ~45 000 chars |
| 70 clusters | ~150 000 chars (~38K tokens) |
| 328 clusters | ~360 000 chars (~90K tokens) |

The only ceiling is `infochat.summary.cluster-cap` — 500 posts on
`remote-llm`, so the worst case is the digest's entire text in one call.
On `pi`, against a small local model, it overruns the context window far
sooner. The roll-up therefore fails exactly when the category is large,
which is exactly when a reader needs it.

The second half is the instruction at `:191`:

> "Name the themes across the clusters below in one or two short
> sentences."

One or two sentences is right for a 4-cluster category and useless for a
300-cluster one. A fixed budget applied to a large, genuinely
multi-threaded category is what produces "various AI developments" —
technically a synthesis, no information.

## The fix

Titles only, truncated, with a scaled request:

- **Titles only** — 328 titles is ~6K tokens instead of ~90K. The
  roll-up names themes; titles carry theme signal, bodies carry detail
  the roll-up is explicitly told not to reproduce ("Do NOT re-list the
  items").
- **Scaled length** — 1 sentence up to 5 clusters, 2 up to 20, 3 up to
  75, 5 beyond. Banded rather than continuous so the value is
  reproducible and reviewable.
- **Named threads, no filler, no numbers.**

## Why no sub-clustering

An earlier draft grouped a category's clusters in embedding space so the
roll-up could state real sizes — "70 papers on context management". That
was driven entirely by wanting trustworthy counts: a model asked to count
328 titles will produce a confident wrong number, printed as fact in a
push message.

Removing quantities from the output removes the reason. Naming 2–4
threads across a title list is well within what a language model does
reliably, and nothing in the output is a checkable claim. That deletes an
embedding-space grouping stage, a new prompt stage, and a fallback for
the embedding-less posts D22 permits — for an output difference of
"mostly context management, with a second thread on transformer
architecture" instead of "70 papers on context management".

The cost is honest: threads are the model's judgement, not measured
structure, so two runs could name them slightly differently. The roll-up
is prose, which already sits outside the D19 determinism boundary; no
part of content SELECTION changes.

## Relationship to M1-714

M1-714's `out_of_scope` names this exact line:

> The two LLM-PROMPT call sites, which must keep receiving the full
> untruncated title: `SummaryProseGenerator.java:179` (summarizer
> prompt) and `CategoryRollupGenerator.java:199` (digest rollup
> prompt). Truncating either would have the model summarize a fragment.

That reasoning holds for the summarizer and does not transfer. The
summarizer describes ONE cluster, where a cut title removes a large
fraction of what it has to work with. The roll-up sees every cluster in
the category and is told not to re-list them; a 24 000-character nitter
title contributes no more theme signal than its first 200 characters, and
crowds out several hundred other titles that would.

So this ticket revises that entry **for the roll-up call site only**, and
does so by editing M1-714's ticket file rather than leaving two documents
disagreeing — hence `blocked_by: M1-714` and its presence in
`files_scope`. `SummaryProseGenerator.java:179` keeps the full title and
M1-714's test pinning that stays green.
