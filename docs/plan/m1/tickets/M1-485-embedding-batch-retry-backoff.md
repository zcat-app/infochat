---
id: M1-485
title: "Embedding batch retry has no backoff, unlike the sibling entity stage"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing the embedding model, batch size, or the retry count itself — only the missing backoff between attempts is added."
acceptance:
  - >-
    EmbeddingWorker applies the same RetryBackoff (sleepBeforeRetry) between
    embedding attempts that the sibling EntityExtractorWorker already applies
    (EntityExtractorWorker.java:243), instead of firing the second attempt
    immediately (EmbeddingWorker.java:300-318). A transient embedding failure no
    longer burns both attempts back-to-back and prematurely releases the post
    with embedding_done=TRUE and no vector.
  - >-
    A test (clock/backoff pinned) asserts the backoff is invoked between embedding
    attempts and that two genuine failures do not release a vectorless post
    differently than the entity stage does.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerBackoffTest.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 295
      removed: 17
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-485: Embedding batch retry has no backoff, unlike the sibling entity stage

## Context

From `/deep-code-review full` (2026-06-27), report
`02-main-infochat-collector-00.md#F1` (medium, verified at source).
`EmbeddingWorker` (`:300-318`) has no `RetryBackoff` and fires its second
`attemptEmbed` immediately, while the sibling `EntityExtractorWorker.java:243`
calls `retryBackoff.sleepBeforeRetry()`. Two back-to-back failures permanently
release the post (`embedding_done=TRUE`, no vector). The fix mirrors the entity
stage's existing backoff.

## Acceptance

See frontmatter. Add the sibling's backoff between embedding attempts; pin it in
a test.

## Out-of-scope

See frontmatter. No model/batch/retry-count change.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 02#F1.
- `EntityExtractorWorker` is the in-repo reference for the backoff shape.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-485-*.md
```
