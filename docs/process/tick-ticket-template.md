---
id: M<N>-NNN
title: <imperative title, ≤ 60 chars>
status: pending                # pending | in-progress | in-review | escalated | done | deferred | abandoned
created: <YYYY-MM-DD>          # set on first save; never edited afterwards
last_updated: <YYYY-MM-DD>     # auto-updated by the /tick skill on every status transition
flow: tick                     # distinguishes tick-flow tickets from m1-flow tickets for measurement
reproduction:
                               # MANDATORY (tick-lint BLOCKER). The executable statement of
                               # the wrong behavior, written and RUN RED before the ticket
                               # is filed (workflow §0). Either the fully-qualified name of
                               # the failing test:
                               #   ChatAgentReplyLanguageTest.replyToACzechScopeIsCzech
                               # or, for a diff mvn verify cannot cover, the exact probe
                               # command plus its observed wrong output.
                               # tick-lint resolves named tests in-tree. A child of a 2+
                               # decomposition whose test cannot exist yet carries the
                               # literal marker `to-be-written` next to the intended
                               # Class#method; a test written and run RED but held out of
                               # the tree carries `parked: <path>` (path must exist).
                               # `start` converts the marker — write/restore the test, run
                               # it RED — before any fix code (workflow §0).
analysis_ref: self
                               # MANDATORY (tick-lint BLOCKER). Every ticket comes out of
                               # /tick analyze, which runs on the `reproduction:` above.
                               # `self` when the ticket body IS the analysis; otherwise the
                               # path of the shared analysis document for a 2+ ticket
                               # decomposition: docs/plan/<milestone>/tick-analysis/<slug>.md.
blocked_by: []
files_scope:                   # OPTIONAL, and never load-bearing. Supporting evidence only.
                               # It carries NO review consequence (no membership FAIL, no
                               # negative-space check) and does NOT qualify a ticket for
                               # --parallel: that requires a different Maven module from
                               # every in-flight ticket (workflow §1).
complexity: low                # low | medium | high; high → round_cap 3 allowed, commit-time verify re-run
risk: low                      # low | medium | high; high → commit-time verify re-run
round_cap: 2                   # default 2; 3 only for complexity: high or risk: high
security_relevant: false       # true → the review gate MUST run the SECURITY check with full force
                               # (it runs anyway; this flag raises the bar for dropping findings)
migration_touch: false         # true → ticket touches Flyway migrations; serializes parallel start
out_of_scope:
  # Explicit, semantic exclusions: features or paths this ticket MUST NOT
  # touch. MANDATORY (tick-lint BLOCKER). Examples:
  # - infochat-provider/**
  # - backfill of the existing corpus
  # - any provider-side unescape (a second, drifting decoder)
acceptance:
  # Runnable or testable items. EACH item MUST name its verification:
  # a named test method/class the diff must add and pass, a runnable
  # command, or a probe. Unverifiable prose is a tick-lint BLOCKER.
  # The FIRST item is the `reproduction:` test, now passing.
  # On a spec-bearing ticket, an item SHOULD cite a spec_refs entry (WARN).
  # At least one item SHOULD be a failure-mode test beyond the reproduction —
  # one that feeds the diff's own production code a hostile/edge input and
  # asserts the behavior that would otherwise break (WARN).
  #   - ChatAgentReplyLanguageTest.drivesWrongLanguageGeneratorAndGetsScopeLanguage
  #     (P3) passes — feeds a stub generator returning English into a cs
  #     scope and asserts the reply is Czech
  #   - docs/spec/commands.md §Content: /summary over-limit notice never
  #     suggests a window wider than the caller's
  #   - mvn verify from repo root is green
test_plan:
  adds:
    # - <module>/src/test/java/.../FooIT.java   (must map to acceptance items)
  preserves:
    - all tests currently green on main
spec_refs:
  # The spec sections this ticket implements. MANDATORY and non-empty on a
  # spec-bearing ticket — one that changes what the system promises; legally
  # EMPTY on a defect ticket, whose contract is its `reproduction:`. Every
  # entry that IS present must resolve (tick-lint BLOCKER). The Approach is
  # derived from these; a spec conflict is a spec-amend, never a bend.
  # - docs/spec/<file>.md §<section>
decision_refs:
  # Explicit decision IDs this ticket carries forward (optional).
  # - D44

# --- Lineage fields (populated by the /tick skill during escalations; usually empty) -----
decomposed_from: M<N>-XXX      # the parent ticket/analysis this was split out from
replaces: M<N>-XXX             # the prior ticket this rewrites
replaced_by: M<N>-AAA          # set on the OLD ticket when refine produces a new one
deferred_on: M<N>-XXX          # (status: deferred) the blocker this is paused on
deferred_reason:               # blocked-on-new-ticket | spec-amend | decomposed | blocked-on-external-measurement
abandoned_reason:              # decomposed | superseded | obsoleted-by-spec-amend | wont-do-infeasible
spec_amend_for: docs/spec/X.md §Y    # set when this ticket exists to amend the cited spec section
spec_amend_parent: M<N>-XXX    # the ticket waiting on this amendment
remediates: M<N>-XXX           # set on a remediation ticket created from a review finding

# --- Dynamic fields (populated by the /tick skill; start empty) -------
reviews: []                    # latest review only: {round, date, verdict, checks, diff_stats}
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}              # pre-flight result populated by start (lint verdict + self-check)
escalation_reason:             # scalar; set by escalate while status: escalated; cleared on resolution
---

# M<N>-NNN: <title>

## Context

The problem, with the observed evidence. One paragraph: what is wrong, who
sees it, what it costs. Cite the analysis document (`analysis_ref:`). Do not
restate the spec — cite `spec_refs:`.

## Root cause

The verified cause, with evidence. Every claim about existing code must cite
the code (path:line) or the grep that returns the hit — code, config and git
outrank committed docs. If the root cause is not fully proven, say what is
proven and what remains to be verified at implementation (and why the ticket
is still safe to start).

## Pitfalls

The traps this change can fall into. Enumerated P1..Pn, numbered
consistently with the analysis document. Each pitfall names: the trap, why
it bites here (which rule/control/threat-model promise it violates), and —
implicitly — the verification that will catch it (see Verification).

- P1: <trap> — <why it bites here>
- P2: ...

## Approach

The chosen solution, derived from `spec_refs:` — spec first, never the
implementation bending the spec. Includes:

- **Files to touch** (the plan — guidance, not an allowlist; departure from
  it is a hurdle, see tick-workflow.md §3)
- **Steps in implementation order**, with the reason for the order
- **Controls to preserve** (engineering-rules §10): sanitize/redaction
  calls, audit emissions, authorization checks, validation, and the tests
  that pin them — enumerated here so they are not improvised at
  implementation time
- **Pitfall→mitigation mapping** (each Pn: the step that avoids it)

## Definition of done

Mirror of the YAML `acceptance:` list in prose. "Done" means every item
here, each verified by its named test/command/probe — including the
failure-mode items.

## Verification

One entry per pitfall Pn and per acceptance item, naming the concrete test
that would catch a violation (negative tests mandatory — a test that feeds
the code the failing input and asserts the protected behavior). Do not
describe happy-path-only coverage; if a pitfall has no catching test, the
ticket is not ready.

- P1 → <TestClass.method> — <what input it feeds, what it asserts>
- P2 → <probe/command> — ...
- acceptance item N → <named test / command>

## Out-of-scope

Prose explanation of what `out_of_scope` covers and why. Specific about what
is intentionally NOT done here so the implementor does not accidentally do
it. If this ticket modifies a pre-existing test, name it here with the new
expected behavior (unauthorized test edits are engineering-rules §8
violations).

## Census

**Required when this ticket fixes or guards a CLASS of defect** — more than
one site of the same shape, or a guard that covers a set. Enumerate the
class mechanically with a re-runnable grep and dispose of every site it
returns (fix / guard / defer: M<N>-XXX / out-of-scope: <reason>). Every
returned path needs a row.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start <id>`:

```bash
python3 scripts/tick-lint.py docs/plan/<milestone>/tick-tickets/M<N>-NNN-*.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Full check table: `docs/process/tick-workflow.md` §1.
