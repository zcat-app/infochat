---
id: M1-077
title: BootstrapLoader tag normalization alignment with TagVocabulary
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParserTest.java
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
  - docs/spec/architecture.md §Bootstrap sources
decision_refs: []
---

## Context

`BootstrapLoader.normalizeTag` applies only NFC + lowercase, missing the character-class validation (`^[a-z0-9][a-z0-9-]{0,47}$`) that `TagVocabulary.normalize` and `TaggerWorker.normalizeTag` both enforce. Invalid tags from bootstrap-sources.json either crash at the DB CHECK constraint (aborting the entire bootstrap transaction) or create rows that TagVocabulary silently ignores at load time.

## Fix approach

Add a validation step after NFC + lowercase that checks against `TagVocabulary.TAG_NAME_PATTERN`. Throw `IllegalStateException` with a clear message naming the invalid tag, so the operator gets a fast-fail at startup rather than a cryptic DB constraint violation mid-transaction.
