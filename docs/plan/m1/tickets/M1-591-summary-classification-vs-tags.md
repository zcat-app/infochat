---
id: M1-591
title: "/summary cluster block prints classification: and tags: as two identical lines — collapse the redundancy"
status: abandoned
created: 2026-07-08
last_updated: 2026-07-08
replaced_by:
  - M1-597
  - M1-598
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - docs/design/03-commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    OPTION (a) — reviving the design's DISTINCT classification field. The design
    (03-commands.md §`/summary`, 05-llm §5.4.4) shows classification as an enum
    picked from {factual, opinion, technical, urgent, ongoing}, which is a
    different concept from tags. But that enum has NO deterministic data source:
    there is no `post.classification` column (grep docs/design/02-schema.md — the
    post table has `tags TEXT[]` and `bootstrap_tags` lives on `source`, but no
    classification column), and the renderer makes only `summary:` LLM-authored
    (the other fields are deterministic). Producing a real classification would
    need a new schema column + migration + tagger/summarizer plumbing — a separate
    feature, not this cleanup. This ticket does NOT add it.
  - >-
    Projecting `source.bootstrap_tags` into the digest to fill the second line
    (the "classification = LLM post.tags, tags = fixed source bootstrap_tags"
    idea). That would (1) contradict the design, which already fixes `tags:` =
    post tags ("tags: comma-separated from the post tags", 05-llm §5.4.4:324), and
    (2) expand the DETERMINISTIC retrieval query — `EligiblePostQuery.selectPosts`
    would have to SELECT `s.bootstrap_tags` and widen the `Post` record. Touching
    the deterministic /summary SQL is out of scope for this low-risk render-layer
    fix. Do NOT touch EligiblePostQuery.java or its record shape.
  - >-
    The summarizer prompt line at docs/design/05-llm-and-embeddings.md §5.4.4:323
    ("classification: pick from {factual, opinion, technical, urgent, ongoing}").
    The renderer never consumes the LLM's classification output — that prompt line
    is pre-existing decoupled text. Per the surgical-changes rule (don't touch
    pre-existing content your change didn't create), leave it; if the prompt/output
    mismatch warrants cleanup, file a follow-up. This ticket updates only the
    03-commands.md §`/summary` OUTPUT-STRUCTURE sample, which shows the emitted
    bytes.
  - >-
    The /retry replay path, the sanitize→translate ordering on `summary:`, the
    score-plural template, the cluster cap, and every other cluster-block field.
    They are unchanged — this ticket removes exactly one emitted line and its two
    bundle strings; the byte-identical-replay property (D19/D36) is preserved
    because the shared renderer emits the same NEW shape at both call sites.
acceptance:
  - >-
    The rendered /summary (and /retry) cluster block no longer emits two identical
    tag lines. Today ClusterBlockRenderer.appendClusterBlock (around lines 101-106)
    appends BOTH a `classification:` line and a `tags:` line, each from the same
    helper `joinedTags(posts)` (the dedup union of `cluster.posts.tags`), so the
    two lines are ALWAYS byte-identical (verified live 2026-07-08). The fix removes
    the redundant `classification:` line and keeps the design-correct `tags:` line
    (post-tag union — matches 05-llm §5.4.4 "tags: comma-separated from the post
    tags"). After the change a block contains exactly one tag-derived line
    (`tags: ...`) and NO `classification:` line.
  - >-
    The now-unused bundle key is removed from BOTH locales per the D43 bilateral
    keyset invariant: `reply.summary.cluster.classification_label` is deleted from
    en.properties AND cs.properties, and the
    `REPLY_SUMMARY_CLUSTER_CLASSIFICATION_LABEL` constant (plus its javadoc) is
    removed from BundleKeys.java. No other bundle key changes. BundleLoaderTest's
    en/cs twin-keyset check stays green (the key is dropped from both files
    together, never one).
  - >-
    docs/design/03-commands.md §`/summary [tag] [-w 24h]` output-structure sample
    (the plain-text block that currently shows `classification: technical, urgent`
    directly above `tags: security, ai`) is updated to drop the `classification:`
    line, so the documented output matches the emitted bytes. This is the only
    design edit; it is a doc-follows-code amendment coordinated with the render
    change (hence a ticket, not a bare `spec:` commit).
  - >-
    NAMED TEST: ClusterBlockRendererTest's byte-for-byte assertions are updated in
    BOTH en (`enClusterBlockRendersLabelsAndSingularScoreByteForByte`) and cs
    (`csClusterBlockRendersTranslatedLabelsByteForByte`) to the new block shape —
    the `classification: a` / `klasifikace: a` line removed, the `tags: a` /
    `tagy: a` line retained — pinning that the duplicate line is gone and that no
    other bytes shifted. SummaryCommandHandlerTest's `assertTrue(body.contains(
    "classification:"))` (line ~227) is updated to assert the block no longer
    carries a `classification:` line (and still carries `tags:`). Red-before on the
    old byte-identical assertions, green-after on the new shape.
  - mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
      — update the en and cs byte-for-byte block assertions to the new shape
      (classification line removed, tags line kept).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
      — flip the `classification:`-present assertion to assert it is absent while
      `tags:` remains.
  preserves:
    - all tests currently green on main
    - >-
      BundleLoaderTest's D43 en/cs twin-keyset assertion (the key is removed from
      both locales, so the keysets stay identical).
    - >-
      ClusterBlockRendererTest's score-plural cases (en singular/plural, cs
      three-form) — untouched by this change.
spec_refs:
  - docs/design/03-commands.md §`/summary [tag] [-w 24h]`
  - docs/design/05-llm-and-embeddings.md §5.4.4 Summarizer (cluster mode)
decision_refs:
  - D43
  - D19
  - D36
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-591: /summary prints classification: and tags: as two identical lines

> **ABANDONED 2026-07-08 — superseded by M1-597 + M1-598.** This ticket chose to
> DELETE the always-duplicate `classification:` line. It was implemented and
> merged (`2b7fd5ac`), then **reverted** (`57da696a`, `77e96fee`) on the
> decision that classification — a distinct per-post enum designed from day one —
> should be given a real data source, not removed. The proper implementation is
> split across M1-597 (collector ingest stage + `post.classification` schema) and
> M1-598 (provider render). This ticket is terminal; do not reopen or re-run it.

## Context

Found 2026-07-08 during live testing. Every `/summary` cluster block renders a
`classification:` line and a `tags:` line whose values are **always identical**,
because `ClusterBlockRenderer.appendClusterBlock`
(`infochat-provider/.../command/ClusterBlockRenderer.java`, around lines 101-106)
builds BOTH from the same helper `joinedTags(posts)` — the dedup union of the
cluster's `post.tags` (the LLM tagger output):

```java
// classification: comma-joined union of cluster.posts.tags.
out.append(bundleLoader.get(REPLY_SUMMARY_CLUSTER_CLASSIFICATION_LABEL, ...))
   .append(' ').append(joinedTags(posts)).append("\n");
// tags: deduplicated union of cluster.posts.tags.
out.append(bundleLoader.get(REPLY_SUMMARY_CLUSTER_TAGS_LABEL, ...))
   .append(' ').append(joinedTags(posts)).append("\n");
```

So the same value is shown twice under two labels — redundant and confusing to
the reader:

```
classification: security, ai
tags: security, ai
```

The two concepts *were* designed to be distinct. `docs/design/03-commands.md`
§`/summary` shows `classification: technical, urgent` above `tags: security, ai`
(different values), and `05-llm §5.4.4` describes `classification:` as an enum
"pick from {factual, opinion, technical, urgent, ongoing}" — a different thing
from the controlled-vocabulary tags. But that classification enum was **never
implemented deterministically**: there is no `post.classification` column
(`02-schema.md`), and the renderer makes only `summary:` LLM-authored. The
implementation filled the designed-but-unbacked `classification:` slot with a
copy of the tags — hence the duplication.

## The fix

Remove the redundant `classification:` line from the renderer and keep the
design-correct `tags:` line (which already matches `05-llm §5.4.4`: "tags:
comma-separated from the post tags"). Drop the now-dead bundle key
`reply.summary.cluster.classification_label` from **both** `en.properties` and
`cs.properties` (D43 bilateral keyset) and its `BundleKeys` constant, and update
the `03-commands.md` §`/summary` output-structure sample to match the emitted
bytes.

The byte-identical-replay property (D19/D36) is preserved: the renderer is shared
by `/summary` and `/retry`, so both call sites emit the same new shape.

## Decision — why drop, not distinguish

Two candidate fixes were weighed (see frontmatter out-of-scope for the full
rationale):

- **(a) Make the two lines distinct.** Rejected/deferred. The design's
  classification enum has no persisted data source (no `post.classification`
  column) — reviving it is a schema + migration + tagger-plumbing feature. The
  only other distinct data, `source.bootstrap_tags`, (1) would contradict the
  design's fixed `tags:` = post-tags meaning and (2) would force the DETERMINISTIC
  `EligiblePostQuery` SELECT/record to widen — out of scope for a render-layer
  cleanup.
- **(b) Drop the redundant line.** Chosen. Minimal, low-risk, keeps the one
  design-correct line, and removes the reader-confusing duplicate. The `tags:`
  line already carries the post-tag union the design specifies.

## Out-of-scope

See frontmatter. Notably: no new `post.classification` schema/migration, no
`source.bootstrap_tags` projection into the deterministic /summary query
(EligiblePostQuery untouched), and no edit to the `05-llm §5.4.4` summarizer
prompt line (pre-existing decoupled text — the renderer never consumed the LLM's
classification output; a follow-up may reconcile it).

## Notes

- **Provenance.** Live-test finding 2026-07-08 (SimpleX test-user walkthrough).
  Not a red-team finding.
- **D43.** The bundle key is removed from `en` and `cs` in the same change so the
  bilateral keyset stays balanced and `BundleLoaderTest` stays green.
- **D19/D36.** Removing one line changes the block bytes consistently at both the
  `/summary` and `/retry` call sites (one shared renderer), so byte-identical
  replay holds; the `ClusterBlockRendererTest` byte-for-byte pins are updated to
  the new shape.
