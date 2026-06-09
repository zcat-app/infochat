---
id: M1-279
title: "§7/§7a sweep: defensive checks, broad catches, test seams"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 20
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - System-boundary validation (adapter inbound, HTTP, config parsing, SQL deserialization, LLM tool args, file I/O) — guard clauses there are correct and stay.
  - The SsrfGuardedHttpClient isZero/isNegative constructor legs — explicitly kept per the report.
  - Behavior changes of any kind — removing a dead check must not change what a legal caller observes.
acceptance:
  - "SsrfGuardedHttpClient constructor null-checks on parameters the package's null-marked contract already forbids are removed (the isZero/isNegative range legs stay)."
  - "The three catch (RuntimeException | JsonProcessingException) blocks around ObjectNode assembly in the LLM adapter are narrowed to the exceptions the assembly can actually throw."
  - "Production null-checks that exist only to serve plain-JUnit test subclasses (e.g. InboundRouter:381 registeredContactSet != null, with a comment admitting it) are removed; the tests are restructured to honor the production non-null contract (top-level package-private doubles per the established pattern, not inner-class fakes)."
  - "The fable5-06#F4/#F6 sites (boundary-populated records with non-null fields that production fills with null; internal defensive null-checks) are verified per-site against the current code — the report did not individually re-verify them — and fixed where real; sites found already-clean are listed in the commit message."
  - "NullAway/Error Prone stay at ERROR across all modules; mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - "infochat-*/src/test/** — test subclasses/doubles that pass null through the removed defensive seams (e.g. the plain-JUnit InboundRouter subclasses behind the :381 check), restructured to honor the production non-null contract; exact files are the sweep's outcome, enumerated in the commit message"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-279: §7/§7a sweep: defensive checks, broad catches, test seams

## Context

Deep-review v4's **T-7A** low sweep (`deep-code-review/v4/UNIFIED-REPORT.md`
§3; sources `deep-code-review/v4/mimo/report.md` SSRF-007,
`deep-code-review/v4/fable5/04-module-infochat-llm-adapter.md#F5`,
`deep-code-review/v4/fable5/07-module-infochat-provider.md#F11`,
`deep-code-review/v4/fable5/06-module-infochat-collector.md#F4/#F6`): the
CLAUDE.md §"No defensive code" and §"Method parameter contracts" rules have
verified violations — internal null-checks the null-marked packages make
illegal, catch clauses wider than what can throw, and production guards that
exist only because test subclasses pass null. The last category is the
worst shape: production code deferring to tests that violate its contract.

## Acceptance

See frontmatter. The budget is numeric-only (no files_scope): the sweep's
exact file set is discovered by the sweep; the named sites are the anchors.

## Out-of-scope

See frontmatter. The reviewer applies §7 narrowly — boundary guards stay;
only internal-to-internal paranoia goes.

## Notes

- Test restructuring (third acceptance item) is authorized test
  modification: the doubles passing null get real instances or the
  established top-level package-private double pattern (avoid inner-class
  fakes per the recorded rule). List every modified test file in the commit.
- The two unverified fable5 site groups (F4/F6) follow the recorded
  premise-verification rule: verify each at the source before changing it;
  a premise that fails verification is dropped with a note in the commit
  message, not "fixed" anyway.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-279-*.md
```
