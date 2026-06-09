---
id: M1-268
title: "Thread /lang through bundle lookups (D43)"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 60
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - The digest path — DigestWorker already passes a language; unchanged.
  - Translating source post bodies — never translated, per the key conventions.
  - Adding languages beyond the shipped en+cs bundles.
  - TranslationProvider SPI and the /lang command itself — the language *setting* machinery works; only the bundle *lookup* sites are wrong.
acceptance:
  - "User-facing reply paths resolve bundle strings with the requester's effective scope language: the one-arg English-only BundleLoader accessor has no remaining production call sites on user-visible reply paths (removed or visibly demoted to internal/log-only use)."
  - "The hardcoded English reply literals in ExportCommandHandler and the InboundRouter pre-registration replies move to bundle keys resolved per scope language."
  - "ChatMemoryPreFetcher.extractKeywords no longer strips all non-ASCII: a named test asserts Czech keywords survive extraction so memory pre-fetch works for a cs scope."
  - "A named end-to-end style test asserts a /lang cs user receives the Czech bundle string from at least one representative handler in each handler group (command replies, chat-path notices, error replies)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "infochat-provider/src/test/** — language-threading tests in the bundle + chat packages (representative per-handler-group /lang cs assertions; extractKeywords non-ASCII test); exact files are the sweep's outcome"
  modifies:
    - "infochat-provider/src/test/** — existing tests asserting English reply strings on paths that now localize, updated to pass the default en scope or assert by bundle key (sweep-determined; enumerate via the behavior-reversal grep before finalizing)"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D43
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-268: Thread /lang through bundle lookups (D43)

## Context

Deep-review v4 verified HIGH **H7** (`deep-code-review/v4/UNIFIED-REPORT.md`
§1; sources `deep-code-review/v4/opus-47/07-module-infochat-provider.md#F1/#F6`,
`deep-code-review/v4/fable5/07-module-infochat-provider.md#F9`): of 340
`bundleLoader.get(` call sites in provider main (across 43 files), 334 use the
one-arg English-only overload; only `DigestWorker` passes a language. v1 ships
`en`+`cs` bundles and the two-arg accessor exists, so a `/lang cs` user sees
English everywhere except the digest — D43 is broken in practice.
Fold-ins per the report: the hardcoded English literals in
`ExportCommandHandler` and the `InboundRouter` pre-registration replies, and
(from the misc-lows list, opus-47) `ChatMemoryPreFetcher.extractKeywords`
stripping all non-ASCII, which kills memory pre-fetch for `cs`.

## Acceptance

See frontmatter. The contract is "the language a scope chose is the language
its replies render in", pinned by representative end-to-end assertions rather
than per-site greps.

## Out-of-scope

See frontmatter. Existing tests that assert English reply strings on paths
that now localize are authorized for modification (they should pass the
default `en` scope or assert by bundle key) — sweep for them per the
behavior-reversal rule before finalizing.

## Notes

- **This is the report's T7 "large cross-cutting ticket (likely split per
  handler group)".** `complexity: high` triggers the plan-writer at start; the
  expected outline outcome is either (a) one mechanical sweep diff if the
  threading is uniform (handler signature already carries scope → language is
  one lookup away), or (b) a decomposition proposal into per-handler-group
  subtickets under the umbrella convention. Do not begin the sweep before the
  outline settles which.
- The mechanical core: most handlers already have the scope at hand; the
  effective-language lookup likely belongs once in the dispatch layer
  (InboundRouter / command context), passed down, rather than 334 per-site
  DB lookups. That is the design question the outline must answer.
- `files_budget: 60` is deliberately roomy for a 43-file sweep + bundles +
  tests; the budget is numeric-only (no files_scope) because the sweep's
  exact file set is its outcome, not its input.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-268-*.md
```
