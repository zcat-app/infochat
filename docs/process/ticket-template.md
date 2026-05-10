---
id: M<N>-NNN
title: <imperative title, ≤ 60 chars>
status: pending                # pending | in-progress | in-review | escalated | done | deferred
created: <YYYY-MM-DD>          # set on first save; never edited afterwards
last_updated: <YYYY-MM-DD>     # auto-updated by the milestone-driver skill on every status transition
blocked_by: []
files_budget: 8                # numeric upper bound; max files this ticket may touch (incl. tests).
                               # Reviewer FAILs SCOPE-DRIFT-CHECK if exceeded. Numeric is canonical.
                               # Umbrella tickets (per docs/process/workflow.md §Ticket-ID placeholder
                               # convention — the umbrella + subticket idiom) typically declare a small
                               # budget (e.g. 2–3) covering only the whole-topic integration test files;
                               # the subtickets carry the implementation budget.
files_scope:                   # OPTIONAL path/glob list. When present, the reviewer ALSO performs
                               # the negative-space check (files in scope NOT touched are surfaced
                               # as PASS or WARN). Omit when the budget is purely numeric.
  # - infochat-collector/src/main/java/.../rss/**
  # - infochat-collector/src/test/java/.../rss/**
complexity: low                # low | medium | high
risk: low                      # low | medium | high
round_cap: 2                   # default 2; opt-in to 3 only for complexity:high or risk:high
security_relevant: false       # true → triggers threat-actor review (see /redteam skill)
migration_touch: false         # true → ticket touches Flyway migrations; serializes parallel start
out_of_scope:
  # explicit list of paths/features this ticket MUST NOT touch.
  # Examples:
  # - infochat-provider/**
  # - migrations under V99__*
  # - any file not listed here that is outside files_budget
  #
  # Subtickets of an umbrella (per docs/process/workflow.md §Ticket-ID placeholder
  # convention) SHOULD list the umbrella's integration test file(s) here so the
  # reviewer can confirm a subticket's diff doesn't pre-empt the whole-topic
  # verification the umbrella ticket is reserved to provide.
acceptance:
  # Ideally runnable assertions, not prose. The reviewer will check
  # each one literally. Examples:
  # - "mvn -pl <module> test -Dtest=<TestName> returns success"
  # - "Flyway migration V<NNN>__<name>.sql applies cleanly on a fresh DB"
  # - "grep -rn '<forbidden-pattern>' src/ returns zero matches"
test_plan:
  adds:
    # - <module>/src/test/java/.../FooIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  # The spec sections this ticket implements. Anchors the "why".
  # - docs/spec/<file>.md §<section>
decision_refs:
  # Explicit decision IDs this ticket carries forward.
  # - D44

# --- Lineage fields (populated by the milestone-driver skill during escalations; usually empty) -----
#
# Placeholder convention (see docs/process/workflow.md §Ticket-ID placeholder convention):
#   M<N>-NNN = the operand of the current driver invocation
#   M<N>-AAA, M<N>-BBB = tickets newly created by the current invocation
#   M<N>-XXX, M<N>-YYY = tickets referenced (existed before this invocation)

decomposed_from: M<N>-XXX      # the parent ticket this was split out from (parent = operand of decompose)
replaces: M<N>-XXX             # the prior ticket this rewrites (refine path resulted in a new ticket)
replaced_by: M<N>-AAA          # set on the OLD ticket when refine produces a new one
deferred_on: M<N>-XXX          # the blocker ticket this is paused on (if status: deferred)
deferred_reason:               # one of: decomposed | blocked-on-new-ticket | spec-amend
spec_amend_for: docs/spec/X.md §Y    # set when this ticket exists to amend the cited spec section
spec_amend_parent: M<N>-XXX    # the implementation ticket waiting on this amendment to land
remediates: M<N>-XXX           # set on a remediation ticket created from a /redteam finding on a done ticket
                               # (the original done ticket is NEVER amended; the new ticket carries the fix)

# --- Dynamic fields (populated by the milestone-driver skill; start empty) -------

reviews: []                    # list of {round, date, verdict, checks, diff_stats}
escalations: []                # list of {date, reason, reviewer_verdict_excerpt}
revisions: []                  # populated on `refine` escalations
overrides: []                  # populated on `override` escalations
aborted_attempts: []           # populated by `abort`; one entry per aborted attempt
reopens: []                    # populated by `reopen`; one entry per reopen
redteam_findings: []           # populated by /redteam; one entry per finding
clarity_check: {}              # populated by `start` when ticket-clarity pre-flight runs
---

# M<N>-NNN: <title>

## Context

One paragraph. Why does this ticket exist? What does completing it
unlock? Cite the parent milestone goal in `docs/plan/<milestone>/README.md`
(e.g. `docs/plan/m1/README.md` §M1 milestone goal) if applicable.

## Definition of Done

- Bulleted, testable statements that mirror `acceptance` in plain
  language so a human reading the ticket understands "done" without
  parsing YAML.
- Each item should be checkable against the resulting diff or against
  the test output.

## Implementation notes

Non-binding hints. Pointers to relevant code, design notes, or spec
sections. NOT a step-by-step — the developer agent reads these as
context, not as a recipe.

- Relevant design note: `docs/design/<NN>-<name>.md` §<section>
- Adjacent code: `<path>` (the existing pattern this should match)
- Anything subtle the developer might miss

## Big-picture notes

What the implementer must keep in mind that isn't in the immediate diff.
This is where "small ticket, big-picture aware" gets enforced.

- Future-shape concerns: "this fetcher will later be one of N kinds;
  design the SPI so kind 2 doesn't need to retrofit it"
- Cross-cutting invariants this ticket's surface must respect
- Where the next ticket in the dependency chain picks up

## Out-of-scope expansion

Prose explanation of what `out_of_scope` covers and why. The reviewer
uses this to judge scope-drift. Be specific about what is *intentionally*
not done here so the developer doesn't accidentally do it.

## Authorized test changes

If this ticket modifies any pre-existing test, list them here with the
new expected behavior. The reviewer treats unauthorized test edits as
test-integrity violations (see canonical rules §8).

- (none — this ticket adds tests but does not modify existing ones)

## Alternatives considered

If alternatives were already weighed during planning, record them here
so the developer doesn't re-derive them and so the reviewer understands
why a less-obvious approach was chosen.

- Alt A: <description>. Rejected because <reason>.
- Alt B: <description>. Rejected because <reason>.
