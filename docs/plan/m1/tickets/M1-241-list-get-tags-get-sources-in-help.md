---
id: M1-241
title: "List /get-tags and /get-sources in the /help catalogue"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
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
  - "The two pre-existing full-/help-body golden assertions are updated to include the two new lines in catalogue order: LangCommandIT.langCsRoundtripThroughInMemoryAdapter (enHelp list — full non-probation USER catalogue) and AdapterRouterIT.firstDmAutoRegistersUserAndRepliesWithHelp (expectedHelpBody() — probation subset) each gain HELP_CMD_GET_TAGS_SHORT then HELP_CMD_GET_SOURCES_SHORT immediately after HELP_CMD_STATUS_SHORT. No other golden line changes."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/HelpCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Discovery
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 86
      removed: 13
escalations:
  - date: 2026-06-09
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — implementation-time budget breach. The /help catalogue change is
      correct and complete, but two pre-existing integration tests assert on
      the FULL bundle-composed /help body and now fail on stale golden strings:
        - infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandIT.java
          (enHelp golden list, full non-probation USER catalogue)
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterRouterIT.java
          (expectedHelpBody(), probation subset)
      Both enumerate help.cmd.* keys in catalogue order and need the two new
      keys inserted after HELP_CMD_STATUS_SHORT. Both files are outside the
      closed files_scope (5 paths) and outside test_plan.modifies; fixing them
      takes files-touched from 5 to 7, exceeding files_budget: 6.
revisions:
  - date: 2026-06-09
    reason: budget-breach refine (drafting missed two pre-existing ITs that golden-pin the FULL bundle-composed /help body; the additive catalogue change correctly alters that body, so both assertions need the two new lines — but both files were outside files_scope and test_plan.modifies, taking the diff over files_budget: 6)
    summary: |
      Pre-refine snapshot: files_budget 6; files_scope had 5 entries
      (HelpCommandHandler, BundleKeys, en.properties, cs.properties,
      HelpCommandHandlerTest); test_plan.modifies listed only
      HelpCommandHandlerTest; acceptance had 6 items.

      The draft asserted "all tests currently green on main" stay green
      without sweeping for tests that assert on the COMPLETE /help body.
      Two ITs do (the M1-138 per-tier-/help precedent): both hand-build
      the expected reply by enumerating help.cmd.* keys in catalogue
      order and compare with assertEquals, so any catalogue addition
      breaks their golden string by construction —
        LangCommandIT.java:120 enHelp (full non-probation USER list),
        AdapterRouterIT.java:177 expectedHelpBody() (probation subset).
      The HelpCommandHandlerTest assertions added in the first pass use
      non-exhaustive contains() and were unaffected; only the two
      exhaustive golden ITs failed mvn verify round 1.

      Changes applied in this refine:
        - files_budget: 6 → 8
        - files_scope: +LangCommandIT.java, +AdapterRouterIT.java
          (both provider test paths)
        - test_plan.modifies: +LangCommandIT.java, +AdapterRouterIT.java
        - acceptance: +1 item (item 6) pinning the two golden-string
          updates to HELP_CMD_GET_TAGS_SHORT then HELP_CMD_GET_SOURCES_SHORT
          immediately after HELP_CMD_STATUS_SHORT, no other golden line change
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: WARN
  warnings:
    - "Acceptance item 5 (javadoc removal) is verified only by inspection; no CI gate enforces javadoc content (trivial)."
    - "Acceptance item 1 includes the delegation phrase 'per commands.md §Discovery display order', but the inline position constraint (after /status, before /summary) makes the spec_ref supplementary, not load-bearing."
  blockers: []
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
new help lines; update the two pre-existing full-`/help`-body golden
assertions (`LangCommandIT`, `AdapterRouterIT`) that enumerate the
catalogue keys in order, inserting the two new keys after
`HELP_CMD_STATUS_SHORT`; `mvn verify` is 0.

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
