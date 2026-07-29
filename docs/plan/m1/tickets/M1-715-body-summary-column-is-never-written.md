---
id: M1-715
title: "post.body_summary is never written, yet EmbeddingWorker prefers it as embedding input"
status: pending
created: 2026-07-29
last_updated: 2026-07-29
blocked_by: []
files_budget: 2
files_scope:
  - docs/design/05-llm-and-embeddings.md
  - docs/design/02-schema.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any code change. This ticket's deliverable is a recorded DECISION,
    not an implementation. Whichever way it resolves, the work that
    follows is a separate, gated ticket — populating `body_summary`
    changes what every future embedding is built from, and dropping the
    column is a migration. Neither belongs in an investigation diff
    (see §Notes on the investigation-flow rule).
  - >-
    `EmbeddingWorker.java`. Its read path is correct for the branch it
    actually takes today; the defect is that the other branch is
    unreachable. Do not "fix" it by deleting the null check — that
    pre-empts the decision this ticket exists to make.
  - >-
    `PostPersister.java` and the fetchers. Adding `body_summary` to the
    INSERT is the implementation half, gated behind the decision.
  - >-
    A Flyway migration dropping the column. Same reason.
  - >-
    The embedding model identity, the 768-dim invariant, and
    `EmbeddingMetadataStartupGuard`. Untouched — this is about the
    embedder's INPUT TEXT, not its model or dimensionality.
  - >-
    `M1-714`'s display headline. `body_summary` was evaluated as a
    headline source there and rejected precisely because it is empty;
    that ticket must not start populating it, and this one must not
    change rendering.
  - any code module, any test
acceptance:
  - >-
    `docs/design/05-llm-and-embeddings.md` records the decision for
    `post.body_summary` as one of exactly two outcomes, with its
    rationale: either (a) it SHOULD be populated, naming which component
    writes it and from what, or (b) it is vestigial and the embedding
    input is `title + first 800 chars of body`, full stop.
  - >-
    `docs/design/02-schema.md` agrees with that decision — the column is
    either documented as populated-by-<component>, or marked vestigial
    and slated for removal.
  - >-
    If the decision is (a), a follow-up implementation ticket exists and
    is referenced by id. If (b), a follow-up removal ticket exists and is
    referenced by id. The investigation is not "done" with an unrecorded
    next step.
  - >-
    The decision explicitly addresses the re-embedding question: 9,224
    posts already carry vectors built from `body`, so a change to the
    input text either leaves them inconsistent with newly-embedded posts
    or requires a re-embed. Whichever is chosen is stated, not left
    implicit.
  - >-
    No file outside `docs/` is modified.
test_plan:
  adds: []
  preserves:
    - >-
      All tests currently green on main. A doc-only diff that required a
      test change would mean it was not doc-only.
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/schema.md §Posts and derivatives
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-715: post.body_summary is never written

## Context

`post.body_summary` has existed since the V7 schema. `EmbeddingWorker`
treats it as the *preferred* input for the text it embeds:

```java
// EmbeddingWorker.java:488
if (row.bodySummary() != null && !row.bodySummary().isEmpty()) {
    body = row.bodySummary();
}
```

with the documented contract `title + "\n\n" + (body_summary OR first
800 chars of body)`.

Nothing writes it. `PostPersister`'s INSERT names its columns
explicitly and `body_summary` is not among them:

```
INSERT INTO post (
  id, uid, source_id, upstream_identifier, url, title, body,
  author, published_at, fetched_at, status, ...
)
```

A grep over `infochat-*/src/main` finds one hit in a write context, and
it is the `post_embedding` insert, not `post`. No test writes it either.
Confirmed against the live-test corpus: **0 of 9,236 posts have a
non-empty `body_summary`**, across all five source kinds.

So the first branch of that conditional is dead, and the embedder has
always used `first 800 chars of body`. That is a defensible input — but
it is not the documented one, and nobody chose it.

This surfaced while evaluating headline sources for M1-714: the column
looked like the natural place to get a short summary line from, and it
turned out to be empty everywhere.

## Why it needs a decision rather than a fix

The two obvious "fixes" pull in opposite directions and have very
different blast radii, which is why this is filed as an investigation
and not as an implementation.

**Populate it.** Then every newly embedded post is embedded from
different text than the 9,224 already-embedded posts. Vector neighbours
are compared across both populations, so retrieval quality becomes a
function of when a post was ingested. Doing this properly means a
re-embed of the corpus, which is a bulk LLM/embedder job, not a code
tweak.

**Drop it.** Then the read path simplifies and the documented contract
becomes true, at the cost of a migration and of closing off a cheap
future improvement — a feed-supplied `<description>` is often a better
embedding input than the first 800 characters of a body, particularly
for the `odysee` sources whose bodies average 2,445 characters.

Note also that `EligiblePostQuery` already calls out `body_summary` as a
field it deliberately does not project, so the column has been
considered and skipped at least once elsewhere in the codebase.

## Acceptance

- The decision is recorded in `docs/design/05-llm-and-embeddings.md`,
  one of two outcomes, with rationale.
- `docs/design/02-schema.md` agrees.
- The follow-up ticket (populate, or remove) exists and is referenced by
  id.
- The re-embedding consequence for the 9,224 existing vectors is stated
  explicitly.
- Nothing outside `docs/` changes.

## Out-of-scope

All code. `EmbeddingWorker`, `PostPersister`, the fetchers, any Flyway
migration, the embedding model identity and the 768-dim invariant, and
M1-714's rendering change. See frontmatter.

## Notes

- **Investigation flow.** The deliverable here is a finding plus a
  recorded decision, so it should not traverse the full
  implement → review → redteam → merge cycle as though it were code. If
  the apply-half turns out to exceed what a doc-only diff can carry,
  decompose: close this as an investigation and file the gated
  implementation ticket fresh, rather than growing this one.

- **Do not let this ride along with M1-714.** The two were found
  together and share no files. M1-714 out-of-scopes `body_summary`
  precisely so the rendering fix cannot quietly acquire an embedding
  change; the reverse holds here.

- **The read path is not itself broken.** `EmbeddingWorker` behaves
  correctly today — it takes the fallback branch every time. Deleting
  the null check before the decision is made would foreclose option (a)
  and is out of scope for that reason, not because the check is load
  bearing.
