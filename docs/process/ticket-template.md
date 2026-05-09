---
id: M1-NNN
title: <imperative title, ≤ 60 chars>
status: pending                # pending | in-progress | in-review | escalated | done | deferred
created: <YYYY-MM-DD>          # set on first save; never edited afterwards
last_updated: <YYYY-MM-DD>     # auto-updated by /m1-tick on every status transition
blocked_by: []
files_budget: 8
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

# --- Lineage fields (populated by /m1-tick during escalations; usually empty) -----

decomposed_from:               # ticket ID this was split from, if any (e.g. M1-017)
replaces:                      # ticket ID this rewrites, if any (refine path resulted in a new ticket)
replaced_by:                   # set on the OLD ticket when refine produces a new one
deferred_on:                   # ticket ID this ticket is blocked on, if status: deferred
deferred_reason:               # one of: decomposed | blocked-on-new-ticket | spec-amend | out-of-scope
spec_amend_for:                # set when this ticket exists to amend the spec for a paused parent
spec_amend_parent:             # the parent ticket waiting on this spec amendment

# --- Dynamic fields (populated by /m1-tick; start empty) -------------------------

reviews: []                    # list of {round, date, verdict, checks}
escalations: []                # list of {date, reason, reviewer_verdict_excerpt}
revisions: []                  # populated by /m1-tick on `refine` escalations
overrides: []                  # populated by /m1-tick on `override` escalations
redteam_findings: []           # populated by /redteam; one entry per finding
clarity_check: {}              # populated by /m1-tick start when ticket-clarity pre-flight runs
---

# M1-NNN: <title>

## Context

One paragraph. Why does this ticket exist? What does completing it
unlock? Cite the parent milestone goal in `docs/plan/implementation-plan.md`
§Milestone 1 if applicable.

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
