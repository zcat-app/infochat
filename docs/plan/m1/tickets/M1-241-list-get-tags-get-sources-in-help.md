---
id: M1-241
title: "List /get-tags and /get-sources in the /help catalogue"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The /get-tags and /get-sources handlers themselves (GetTagsCommandHandler, GetSourcesCommandHandler) — M1-231 adds them; this ticket only advertises them in /help and must not alter their dispatch or reply behavior.
  - CommandPermissions.ALLOWED — both names are already in the probation allow-set; leave it.
  - Any other catalogue command's help line, tier, or display position.
  - The bundles' reply.get_tags.* / reply.list_sources.* strings — this ticket adds only the two help.cmd.*.short lines.
acceptance:
  - "HelpCommandHandler.CATALOGUE includes a /get-tags entry and a /get-sources entry, both at the USER tier (any non-banned user), placed in the Discovery group (after /status, before /summary) per commands.md §Discovery display order."
  - "Two new BundleKeys constants (HELP_CMD_GET_TAGS_SHORT, HELP_CMD_GET_SOURCES_SHORT) resolve to a non-empty short-help line in both en.properties and cs.properties (BundleLoaderTest bilateral-completeness check stays green)."
  - "HelpCommandHandlerTest asserts a non-probation USER caller's /help body contains the /get-tags and /get-sources help lines (HELP_CMD_GET_TAGS_SHORT, HELP_CMD_GET_SOURCES_SHORT)."
  - "HelpCommandHandlerTest.probationCallerSeesOnlyAllowedSubsetPlusFooter additionally asserts the probation /help body contains the /get-tags and /get-sources help lines (both are in the probation allow-set)."
  - "The HelpCommandHandler CATALOGUE javadoc no longer states /get-tags and /get-sources are intentionally absent for lack of a v1 handler (the rationale is stale once they dispatch)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-241: List /get-tags and /get-sources in the /help catalogue

## Context

M1-231 implements the `/get-tags` and `/get-sources` handlers, so both
commands now dispatch for any non-banned user. But `HelpCommandHandler`
still omits them from its `CATALOGUE` — the existing javadoc says they
are "intentionally absent — listing a non-dispatchable command would
advertise an unknown-command path." That rationale dies once the
handlers exist: `commands.md` §Discovery defines `/help` as a
"context-aware list of commands available to the caller," and these two
commands are available to every non-banned user (including the
probation tier, where both sit in `CommandPermissions.ALLOWED`). The
welcome and probation bundle strings already name them; `/help` is now
the one discovery surface that does not, leaving the listing
inconsistent with the rest. This ticket closes that residual gap
(flagged during M1-231 as out-of-scope for that ticket).

## Acceptance

See frontmatter. In prose: add `/get-tags` and `/get-sources` to
`HelpCommandHandler.CATALOGUE` at the USER tier, in the Discovery group
(after `/status`, before `/summary`), each backed by a new
`help.cmd.*.short` bundle key present in both `en` and `cs`; correct the
now-stale "intentionally absent" javadoc; extend `HelpCommandHandlerTest`
so the non-probation and probation `/help` bodies both assert the two
new help lines; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The handlers and their reply behavior belong to M1-231;
`CommandPermissions.ALLOWED` already lists both names and is unchanged;
no other catalogue entry moves. The only bundle additions are the two
`help.cmd.*.short` lines — not the commands' reply strings.

## Notes

- Both commands are spec USER tier ("any non-banned user", read-only,
  scope-filtered) — `HelpTier.USER`, like `/status` and `/list-sources`.
- The probation `/help` subset is filtered by
  `CommandPermissions.allowedDuringProbation`; both names are already in
  that set, so once they are in the catalogue they appear in the
  probation listing automatically — the test just needs the positive
  assertions added.
- `HelpCommandHandlerTest.probationCallerSeesOnlyAllowedSubsetPlusFooter`
  uses non-exhaustive `assertContainsLine` / `assertOmitsLine`, so adding
  catalogue entries does not break it; the change is purely additive
  assertions.
- Adjacent pattern: the existing `/status` / `/list-sources` USER-tier
  catalogue entries in `HelpCommandHandler.CATALOGUE`.

## Pre-flight self-check (author-side)

Run before `/m1-tick start M1-241`:

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-241-list-get-tags-get-sources-in-help.md
```
