---
id: M1-882
title: "Reject top nodes as source bootstrap tags"
status: pending
created: 2026-08-18
last_updated: 2026-08-18
flow: tick
reproduction: >-
  to-be-written SourceUpsertServiceIT.topBootstrapTagIsRejectedWithoutWrites —
  today a seeded top `tech` is accepted by `/add-source --tags tech`; after
  two tagger failures its unchanged bootstrap value is persisted in post.tags.
analysis_ref: docs/plan/m1/tick-analysis/tag-top-source-and-unfollow-subtree.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GetTagsCommandHandler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-provider/src/main/resources/bundles/*.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GetTagsCommandHandlerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapLoaderIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Expanding a top to its leaves at source/bootstrap input.
  - Changing top eligibility for /follow-tag, /unfollow-tag, /summary, or search filters.
  - Changing TaggerWorker fallback mechanics or the tag-tree migration.
  - Any raw inbound tag interpolation in friendly replies.
acceptance:
  - "SourceUpsertServiceIT.topBootstrapTagIsRejectedWithoutWrites (the converted reproduction) passes: a normalized existing top `tech` supplied to fresh and admin-replacement /add-source paths causes no source, subscription, tag, or audit write; an existing leaf still succeeds — verification: the named Testcontainers IT (analysis P1/P2; spec: docs/spec/schema.md §Sources and tags)."
  - "AddSourceCommandHandlerTopTagIT.rejectedTopUsesLocalizedLeafSuggestionsWithoutEchoingInput passes at the adapter boundary: `/add-source ... --tags tech` receives the localized restricted-dictionary reply with trusted eligible leaf suggestions, contains no raw supplied token, and writes nothing — verification: named IT across the default bundle plus BundleKeyParityTest (analysis P3; spec: docs/spec/commands.md §Source management; docs/spec/security.md §Friendly errors)."
  - "BootstrapLoaderIT.topBootstrapTagFailsBeforeAnySourceWrite passes: a bootstrap fixture with existing top `tech` fails startup before source/bootstrap-meta/audit writes, identifies the trusted config location and valid leaf remedy, while a leaf-only fixture remains loadable — verification: named Testcontainers IT (analysis P1/P2; spec: docs/spec/schema.md §Sources and tags)."
  - "GetTagsCommandHandlerTest.rendersTopNodesDistinctFromSourceEligibleLeaves passes: `/get-tags` visibly distinguishes top nodes from source-eligible leaves without changing followed markers or hiding either type; bundle/help parity covers every supported locale — verification: named test and Maven bundle parity checks (analysis P4; spec: docs/spec/commands.md §Discovery)."
  - "Failure mode: TaggerWorkerIT.bootstrapFallbackStoresOnlyLeafAfterRejectedTopInputs passes with two tagger failures after a source has been admitted through each supported boundary; the stored fallback array contains leaves only and no top — verification: named Testcontainers IT (analysis P1)."
  - "mvn verify from the repository root is green — verification: command exit 0."
test_plan:
  adds:
    - SourceUpsertServiceIT top-rejection and no-write cases
    - AddSourceCommandHandlerTopTagIT
    - BootstrapLoaderIT top fail-fast case
    - GetTagsCommandHandlerTest top/leaf render case
    - TaggerWorkerIT forced-fallback leaf invariant case
  modifies: []
  preserves:
    - existing source upsert atomicity/audit tests
    - existing BootstrapLoader fail-fast and source-origin tests
    - existing TaggerWorker fallback/retry behavior
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/commands.md §Source management
  - docs/spec/commands.md §Discovery
  - docs/spec/security.md §Failure handling
decision_refs:
  - D5
  - D22
decomposed_from: M1-869
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-882: Reject top nodes as source bootstrap tags

## Context

Source/bootstrap tags are the tagger's deterministic fallback input, so accepting an existing top gives a reachable path to a forbidden top in `post.tags`. The UI must make the narrower source dictionary understandable. Shared analysis: `analysis_ref` P1–P4.

## Root cause

`SourceUpsertService.unknownNodeTags` tests only existence (`SourceUpsertService.java:277-304`), and `BootstrapLoader.failFastOnNonNodeTags` likewise selects all existing tag names (`BootstrapLoader.java:301-337`). Both then write their input to `source.bootstrap_tags`; `TaggerWorker` returns it unchanged after retry exhaustion (`TaggerWorker.java:463-478`). `/get-tags` selects only names (`GetTagsCommandHandler.java:58-60`).

## Pitfalls

- P1: source top acceptance poisons `post.tags` through fallback.
- P2: validation must precede every write and retain existing atomic/fail-fast behavior.
- P3: user-facing rejection must not reflect unvalidated inbound text (M1-656; security friendly-error promise).
- P4: source eligibility must be discoverable without removing valid top follow/filter behavior.

## Approach

- **Files to touch:** the source upsert/command, bootstrap loader, get-tags handler, localized bundles, and named tests.
- First replace node-only checks with leaf-only checks at both write boundaries, returning trusted eligible leaves for the command's existing localized error path. Then render top versus leaf information in `/get-tags`; finally add adapter- and database-boundary tests.
- Preserve URL probe/authorization, source transaction/audit branches, TagNormalizer, bootstrap loader pre-write aggregation, fallback retry/atomic persist, and bundle parity (engineering-rules §10).
- P1→leaf gates plus forced fallback test; P2→zero-write assertions; P3→outbound no-echo assertion; P4→dictionary render test.

## Definition of done

All YAML acceptance items pass: both source paths reject tops before writes, safe localized suggestions explain the restriction, `/get-tags` distinguishes node roles, forced fallback remains leaf-only, and the full build is green.

## Verification

- P1 → failure-mode `SourceUpsertServiceIT.topBootstrapTagIsRejectedWithoutWrites` and `TaggerWorkerIT.bootstrapFallbackStoresOnlyLeafAfterRejectedTopInputs`.
- P2 → failure-mode `BootstrapLoaderIT.topBootstrapTagFailsBeforeAnySourceWrite`.
- P3 → `AddSourceCommandHandlerTopTagIT.rejectedTopUsesLocalizedLeafSuggestionsWithoutEchoingInput`.
- P4 → `GetTagsCommandHandlerTest.rendersTopNodesDistinctFromSourceEligibleLeaves`.
- Failure-mode assertions must not permit a top in a stored fallback array or raw inbound tag bytes in the outbound error.
- Acceptance 6 → `mvn verify`.

## Out-of-scope

No top expansion, follow/search semantics, migration, or fallback algorithm change. Existing tests are not weakened or repurposed; new end-state fixtures use leaves only.

## Census

Class-scoped validation defect. Re-run `rg -n "SELECT name FROM tag|node_kind|bootstrap_tags" infochat-provider/src/main/java/app/zcat/infochat/provider/source infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap infochat-provider/src/main/java/app/zcat/infochat/provider/command` at start. Dispose every source/bootstrap writer as leaf-gated here; `/get-tags` is rendered here; follow/search read sites remain out of scope because tops stay legal there.
