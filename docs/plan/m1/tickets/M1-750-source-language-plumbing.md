---
id: M1-750
title: "Source language plumbing: --lang + bootstrap validation"
status: done
created: 2026-08-02
last_updated: 2026-08-03
refine_log:
  - date: 2026-08-03
    reason: scope-drift regularization at implementation — upsert signature gains `language`, forcing mechanical parameter additions in three test files (fake overrides in AddSourceCommandHandlerTest/AddSourceBanCheckOrderingTest, direct call in AddSourceContactIdRedactionTest); files_scope extended by those three, files_budget 15 → 18
blocked_by:
  - M1-749
files_budget: 18
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
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceBanCheckOrderingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceContactIdRedactionTest.java
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
reviews:
  - round: 1
    date: 2026-08-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      assertion_adequacy: WARN
    diff_stats:
      files: 25
      added: 593
      removed: 41
    notes: >-
      Reviewer cited line numbers relative to the .diff file (e.g. AddSourceArgs
      "--lang=" at "691", actual source line 159) rather than the source files;
      every cited CONTENT claim was verified against the worktree (the described
      code exists only there), so the wrong-tree read is refuted — citations are
      diff-relative, not source-relative. ASSERTION-ADEQUACY WARN (informational):
      bootstrap loader path's language write has no end-of-path assertion in
      BootstrapLoaderIT (pre-existing test, fixture omits the field); surviving
      mutations confined to the trusted operator-JSON path; deferred as noted.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-08-03
    verdict: CLEAN
    base: fork-point-of-branch (working tree vs merge-base main HEAD)
    head: working-tree (0 commits on branch)
    verdict_file: docs/plan/m1/redteam-multi/M1-750-2026-08-03/cross-examination.md
    out_of_model_count: 2
    note: |
      redteam-multi gate (run step 4, auditors claude/codex/opencode, 3/3
      CLEAN, no findings). 2 out-of-model observations in disposition.md:
      (1) operator-vs-caller framing wording, no gap; (2) user-influenced
      ingest-translation cost surface — accepted residual risk, follow-up
      ticket if the ingest-translation wave needs a spend bound; (3) bootstrap
      resolveLanguage trim asymmetry — fail-closed, no ticket.
clarity_check:
  date: 2026-08-03
  verdict: PASS
  warnings:
    - CENSUS-PRESENT-IF-CLASS-SCOPED lint WARN resolved inline — Census section added enumerating AddSourceArgs/SourceUpsertService/Bootstrap* sites
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

## Census

This ticket adds a field to three existing records/parsers and a column to
one INSERT, so it is class-scoped. The classes are "sites that construct or
consume the changed surfaces". Enumerated mechanically — re-runnable greps:

    grep -rn "new AddSourceArgs" infochat-provider/src/main/java
    grep -rln "sourceUpsertService.upsert\|SourceUpsertService" infochat-provider/src
    grep -rln "BootstrapSourcesEntry\|BootstrapSourcesParser\|BootstrapLoader" infochat-collector/src

| Site | Disposition |
|---|---|
| `AddSourceArgs.java:180` (record construction in parse's Success path) | fix — the record gains `lang`; this is the single main-code construction site |
| `AddSourceCommandHandler.java` (parses args, calls `sourceUpsertService.upsert` at :219) | fix — carries `lang` into the upsert |
| `SourceUpsertService.java` (`UPSERT_SOURCE_SQL` INSERT) | fix — column list gains `language` |
| `BootstrapSourcesEntry.java` / `BootstrapSourcesParser.java` (entry field + parse) | fix — optional `language`, default `en` |
| `BootstrapLoader.java` (@Priority(200) startup bean; the INSERT) | fix — INSERT gains `language` |
| `AddSourceArgsTest`, `BootstrapSourcesParserTest`, `SourceUpsertServiceIT` | fix — the ticket's test additions |
| every remaining `SourceUpsertService` hit — `AddSourceCommandHandlerTest`, `AddSourceBanCheckOrderingTest`, `AddSourceContactIdRedactionTest`, `DegradedDigestRendererTest` | fix — mechanical only: the `upsert` signature gains `language`, so the two fake overrides and the redaction test's direct call add the parameter (no behavioral change); `DegradedDigestRendererTest` only names the class — no change |
| every remaining `BootstrapLoader` hit — `BootstrapLoaderTest`, `BootstrapLoaderIT`, plus `@Priority(200)` ordering mentions in `FetchScheduler`, `StreamSourceSupervisor`, `NostrStreamSource`, `TagVocabulary` | out-of-scope — the bean's startup wiring and priority ordering are untouched by the column addition |

The greps are deliberately broad (class-name hits), so the last rows dispose
the incidental references as a class. Re-run them live at `start`/review and
verify no hit is missed.

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
