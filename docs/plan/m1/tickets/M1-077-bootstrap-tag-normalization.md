---
id: M1-077
title: BootstrapLoader tag normalization alignment with TagVocabulary
status: done
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
created: 2026-05-25
last_updated: 2026-05-25
escalations:
  - date: 2026-05-25
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID FAIL: docs/spec/architecture.md §Bootstrap sources does not resolve to any heading in docs/spec/architecture.md. The section "Bootstrap sources" does not exist in that file.
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (06-module-infochat-collector.md#F1)
out_of_scope:
  - any TagVocabulary or TaggerWorker change — their normalization is already correct
  - any bootstrap-assets-related change
  - any new tag validation rule beyond what TagVocabulary already enforces
acceptance:
  - "BootstrapLoader.normalizeTag applies the same character-class validation as TagVocabulary (^[a-z0-9][a-z0-9-]{0,47}$) after NFC + lowercase. Verify: BootstrapLoaderIT.invalidTagInBootstrapJsonFailsFast passes"
  - "An invalid tag in bootstrap-sources.json (e.g. 'machine learning' with a space) causes a clear startup failure with a message naming the invalid tag. Verify: BootstrapLoaderIT.invalidTagInBootstrapJsonFailsFast passes"
  - "Valid tags pass through unchanged. Verify: existing BootstrapLoaderIT tests remain green"
  - "mvn -pl infochat-collector verify is green"
test_plan:
  adds:
    - BootstrapLoaderIT.invalidTagInBootstrapJsonFailsFast (new)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
decision_refs: []
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 72
      removed: 18
  - round: 2
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 91
      removed: 18
revisions:
  - date: 2026-05-25
    reason: clarity-fail refine
    changes: |
      - spec_refs: architecture.md §Bootstrap sources → deployment.md §Bootstrap behavior on startup (anchor resolution fix)
      - files_scope: removed BootstrapSourcesParserTest.java (not touched by this ticket)
      - files_budget: 3 → 2
---

## Context

`BootstrapLoader.normalizeTag` applies only NFC + lowercase, missing the character-class validation (`^[a-z0-9][a-z0-9-]{0,47}$`) that `TagVocabulary.normalize` and `TaggerWorker.normalizeTag` both enforce. Invalid tags from bootstrap-sources.json either crash at the DB CHECK constraint (aborting the entire bootstrap transaction) or create rows that TagVocabulary silently ignores at load time.

## Fix approach

Add a validation step after NFC + lowercase that checks against `TagVocabulary.TAG_NAME_PATTERN`. Throw `IllegalStateException` with a clear message naming the invalid tag, so the operator gets a fast-fail at startup rather than a cryptic DB constraint violation mid-transaction.

## Round 1 rework

1. The new test resource file `bootstrap-sources-invalid-tag.json` is outside `files_scope` and exceeds `files_budget` (3 vs 2). Fix: inline the fixture data in the test (write JSON to a temp file from within the test method) so only the 2 declared files are touched.
