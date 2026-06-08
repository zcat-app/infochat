---
id: M1-231
title: "Implement /get-tags and /get-sources (advertised, no handler)"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 8
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Changing the existing /list-sources behavior or flags — /get-sources is an alias of /list-sources minus --all; /list-sources itself is unchanged.
  - The controlled-vocabulary / tag-mode data model — /get-tags reads existing tag + scope_tag state; no schema change.
  - The probation allow-set membership of these commands (CommandPermissions.ALLOWED already lists them) — leave it; this ticket makes the listing truthful by adding the handlers.
  - Any other command in the catalogue.
acceptance:
  - "A /get-tags handler (name() == \"get-tags\") returns the controlled vocabulary, marking the scope's followed tags per scope_tag/tag_mode, as a deterministic scope-filtered read available to any non-banned user (commands.md §Discovery)."
  - "/get-sources is dispatchable and behaves as an alias of /list-sources accepting the same flags except --all (commands.md §Discovery) — implemented either as a thin GetSourcesCommandHandler delegating to the ListSources logic with --all stripped, or as an InboundRouter alias mapping."
  - "A named handler test asserts /get-tags returns the scope's followed-tag marking for a fixture, scoped per (user, scope)."
  - "A named handler test asserts /get-sources returns the same result as /list-sources for an equivalent invocation and rejects/ignores --all."
  - "Invoking /get-tags or /get-sources as a probation user no longer returns UNKNOWN_COMMAND_REPLY (the welcome and error.probation.blocked bundle strings that advertise them are now accurate)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GetTagsCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GetSourcesAliasTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
  - docs/spec/commands.md §Command catalogue
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-231: Implement /get-tags and /get-sources (advertised, no handler)

## Context

Deep-review finding `deep-code-review/v2.5/opus-48/07-module-infochat-provider.md#F1`
(high MAINTAINABILITY-RULES-DRIFT / spec-drift). `docs/spec/commands.md`
§Discovery commits `/get-tags` and `/get-sources` as v1 commands for "any
non-banned user," with `/get-sources` defined as "an alias of
`/list-sources` accepting the same flags except `--all`." No
`CommandHandler` registers either name (verified: no `name()` returns
`get-tags`/`get-sources`; only `list-sources` exists). The drift is
user-facing and internally inconsistent: `CommandPermissions.ALLOWED`
lists both as probation-permitted, and two bundle strings
(`reply.welcome.dm_fresh` en.properties:100, `error.probation.blocked`
en.properties:167) tell users they work — so a fresh user following the
welcome message verbatim and typing `/get-tags` gets "Unknown command. Try
/help", the exact path `HelpCommandHandler`'s carve-out claims to avoid.

## Acceptance

See frontmatter. In prose: add a `/get-tags` handler (controlled
vocabulary marking the scope's followed tags, scope-filtered, non-banned
tier) and make `/get-sources` dispatchable as a `/list-sources` alias
minus `--all`; named tests pin both; the previously-false advertising on
the welcome/probation surfaces becomes accurate; `mvn verify` is 0.

## Out-of-scope

See frontmatter. `/list-sources` itself, the tag data model, and the
probation allow-set membership are unchanged — this ticket closes the
gap by adding the missing handlers, the spec-favored direction over
removing the advertising.

## Notes

- Recommended implementation sketch is in the source finding.
- Both commands are the spec's "read-only, scope-filtered" tier — cheap
  deterministic DB reads, no LLM, honoring per-(user, scope) isolation.
- `cs.properties` carries the same advertised strings as `en.properties`;
  no bundle edit is needed if the handlers are implemented (the strings
  become true).
