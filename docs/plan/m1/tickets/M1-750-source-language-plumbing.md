---
id: M1-750
title: "Source language plumbing: --lang + bootstrap validation"
status: pending
created: 2026-08-02
last_updated: 2026-08-02
blocked_by:
  - M1-749
files_budget: 15
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/source/SourceLanguageRegistry.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesEntry.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceArgs.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/resources/inbound-reflection-error-baseline.txt
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceArgsTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParserTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
  - docs/spec/commands.md
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
decomposed_from: M1-745
out_of_scope:
  - >-
    The schema, the `IngestTranslationWorker`, and the embedding gate —
    that is M1-749, which lands the `source.language` column this ticket
    writes. No migration here.
  - >-
    Extending the supported source-language set beyond `{en, cs}`. The set
    is a reviewed constant; adding a language is a deliberate one-line
    reviewed change, not something an operator input can do.
  - >-
    `LanguageRegistry`'s user-UI-language set (`/lang`). It stays a UI
    gate; this ticket's `SourceLanguageRegistry` is deliberately separate.
  - >-
    Language DETECTION over post bodies. Language is declared by the
    operator, never inferred.
  - >-
    New bundle keys. The unknown-code rejection reuses the existing
    `error.lang.unsupported_code` key; the only bundle edits are the
    `--lang` mention in the EXISTING `help.cmd.add-source.usage` value
    (en + cs twins together — bilateral parity is CI-enforced).
  - >-
    Entity extraction or any other consumer reading the English field;
    the query leg (M1-746); display-time translation (M1-747).
acceptance:
  - >-
    `bootstrap-sources.json` entries accept an optional `language` field
    (default `en`), parsed through `BootstrapSourcesEntry`/
    `BootstrapSourcesParser` and persisted into `source.language` by
    `BootstrapLoader`'s INSERT (column list gains `language`).
  - >-
    `/add-source` accepts `--lang <code>` in both `--flag value` and
    `--flag=value` forms, parsed in `AddSourceArgs` (its record gains the
    field; today `--lang` falls into the unknown-flag Failure branch),
    carried through `AddSourceCommandHandler` into
    `SourceUpsertService.UPSERT_SOURCE_SQL`, whose INSERT gains the
    `language` column.
  - >-
    `source.language` is INSERT-only at the upsert: the provider role's
    column-scoped UPDATE grant (V31) excludes it, so the
    `ON CONFLICT (kind, identifier) DO UPDATE` does NOT overwrite an
    existing row's language — the `source_origin` precedent.
  - >-
    BOTH entry points validate against a new `SourceLanguageRegistry` in
    infochat-core — a reviewed constant set of supported source languages,
    initially `{en, cs}` — and reject an unknown code rather than silently
    storing it. The rejection is the Failure path, not a silent default.
  - >-
    The `/add-source` user-facing rejection reuses the EXISTING
    `error.lang.unsupported_code` bundle key (cs twin already present;
    `{0}` takes the valid-codes list). Because the codes expression is
    registry-sourced, `InboundReflectionGuardTest`'s auto-clear rules
    (`isTriviallySafe`: literals, `.size()`, same-file static constants
    only) do not cover it, so
    `infochat-provider/src/test/resources/inbound-reflection-error-baseline.txt`
    gains a line of the pinned form
    `AddSourceArgs.java | error.lang.unsupported_code | 0 | <expr> | bot-authored: the reviewed SourceLanguageRegistry constant set`
    — the `LangCommandHandler.java` precedent at baseline line 67.
  - >-
    `help.cmd.add-source.usage` documents `--lang` in BOTH
    `en.properties` and `cs.properties` (same edit, both twins — the
    bilateral keyset parity CI reds on a one-sided change).
  - >-
    Doc drift disposed: `docs/spec/commands.md` §Source management grammar
    documents `--lang <code>`, and `docs/design/07-deployment.md` §7.6.1
    documents the bootstrap entry's optional `language` field (default
    `en`).
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceArgsTest.java
      — `--lang <code>` parses in both `--flag value` and `--flag=value`
      forms; an unknown code is rejected by `SourceLanguageRegistry`
      rather than stored; an unknown `--flag` still fails malformed as
      today.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/bootstrap/BootstrapSourcesParserTest.java
      — optional `language` defaults to `en`; an unknown code is rejected
      by `SourceLanguageRegistry`.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/source/SourceUpsertServiceIT.java
      — the declared language round-trips into `source.language` on
      insert, and a re-upsert (ON CONFLICT DO UPDATE) does NOT overwrite
      it.
  preserves:
    - >-
      InboundReflectionGuardTest's census — the new interpolation site is
      covered by exactly one baseline line, not by weakening
      `isTriviallySafe`.
    - the en/cs bilateral bundle keyset parity CI
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/spec/llm.md §Translation flow
decision_refs:
  - D29
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-750: Source language plumbing — --lang + bootstrap validation

## Context

Decomposed from M1-745 (the parent's body carries the three outline-fail
analyses; nearly every fatal finding lived in this slice — the reflection
guard baseline, the bundle couplings). D29 makes English the corpus anchor
with the language DECLARED by the operator, never detected. M1-749 lands
the `source.language` column and the worker that reads it; this ticket is
how the declared value gets in: the bootstrap file and `/add-source
--lang`, both validated against a single reviewed set.

## Approach

**One registry, two entry points.** `SourceLanguageRegistry` lives in
infochat-core so the collector bootstrap path and the provider command
path enforce the same reviewed constant set, initially `{en, cs}` — the
set M1-749's worker can actually serve. The UI-facing `LanguageRegistry`
(enabled set also `{en, cs}`, but a user-UI-language gate injected with
provider-side `BundleLoader`) is untouched; conflating UI languages with
source languages was rejected in the parent's refine.

**INSERT-only language.** The provider role's column-scoped UPDATE grant
(V31: `status, consecutive_failures, deleted_at, deleted_by,
bootstrap_tags`) excludes `language`, so the upsert writes it on INSERT
and leaves it alone on conflict — the `source_origin` precedent. Changing
an existing source's language is an operator SQL action, not a chat
command, in v1.

**Known couplings, pinned up front** (each cost a plan pass to find):
the registry-sourced `{0}` interpolation needs exactly one
`inbound-reflection-error-baseline.txt` line (`LangCommandHandler`
precedent, baseline:67); and the existing `help.cmd.add-source.usage`
value documents `--lang` in both bundle twins in the same diff.

## Out-of-scope

The schema/worker/embedding gate (M1-749). Extending the supported set
beyond `{en, cs}`. `LanguageRegistry`'s UI set. Language detection. New
bundle keys. Entity extraction, the query leg (M1-746), display-time
translation (M1-747).

## Notes

- Precedent tickets listing the reflection baseline in `files_scope`:
  M1-716, M1-705, M1-741, M1-671 — the file is a routine scope member for
  provider error-path work.
- `error.lang.unsupported_code` exists at
  `infochat-provider/src/main/resources/bundles/en.properties:512` with
  its cs twin at `cs.properties:395`, taking the valid-codes `{0}`
  interpolation.
- `AddSourceArgs.parse` already handles `--flag value` and `--flag=value`
  for `--tags`/`--type`/`--category`/`--name`; `--lang` follows the same
  shape, and unknown `--*` keeps failing malformed as today.
