---
id: M<N>-NNN
title: <imperative title, ≤ 60 chars>
status: pending                # pending | in-progress | in-review | escalated | done | deferred
created: <YYYY-MM-DD>          # set on first save; never edited afterwards
last_updated: <YYYY-MM-DD>     # auto-updated by the milestone-driver skill on every status transition
blocked_by: []
files_budget: 8                # numeric upper bound; max files this ticket may touch (incl. tests).
                               # Reviewer FAILs SCOPE-DRIFT-CHECK if exceeded.
                               # Umbrella tickets (per docs/process/workflow.md §Ticket-ID placeholder
                               # convention — the umbrella + subticket idiom) typically declare a small
                               # budget (e.g. 2–3) covering only the whole-topic integration test files;
                               # the subtickets carry the implementation budget.
                               # LIFECYCLE-PATH EXEMPTION: do NOT count `docs/plan/m1/STATUS.md` or
                               # this ticket's own file in files_budget. /m1-tick writes them
                               # automatically; the reviewer auto-exempts both.
files_scope:                   # OPTIONAL path/glob list. When present, the reviewer ALSO performs
                               # the negative-space check (files in scope NOT touched are surfaced
                               # as PASS or WARN). Omit when the budget is purely numeric.
                               # LIFECYCLE-PATH EXEMPTION: do NOT list `docs/plan/m1/STATUS.md` or
                               # this ticket's own file here.
  # - infochat-collector/src/main/java/.../rss/**
  # - infochat-collector/src/test/java/.../rss/**
complexity: low                # low | medium | high; high triggers the Plan subagent at start
risk: low                      # low | medium | high; high triggers commit-time mvn verify re-run
round_cap: 2                   # default 2; opt-in to 3 only for complexity:high or risk:high
security_relevant: false       # true → triggers threat-actor review (see /redteam skill)
migration_touch: false         # true → ticket touches Flyway migrations; serializes parallel start
out_of_scope:
  # Explicit list of paths/features this ticket MUST NOT touch.
  # Examples:
  # - infochat-provider/**
  # - migrations under V99__*
  # - any file not listed here that is outside files_budget
  #
  # Subtickets of an umbrella SHOULD list the umbrella's integration test
  # file(s) here so the reviewer can confirm a subticket's diff doesn't
  # pre-empt the whole-topic verification the umbrella ticket is reserved
  # to provide.
  #
  # FORWARD-REFERENCE RULE: ticket-ID references here (and anywhere else
  # in the ticket) are validated by the clarity pre-flight. A reference
  # to a ticket that does not yet exist as a file under docs/plan/<milestone>/
  # tickets/ produces a clarity WARN here in prose, or a clarity FAIL when
  # the reference is in a load-bearing frontmatter field (blocked_by,
  # deferred_on, decomposed_from, replaces, replaced_by, spec_amend_parent,
  # remediates). File the follow-up ticket as a skeleton before deferring
  # work to it.
acceptance:
  # Runnable or testable items. The two preferred shapes:
  #
  # (a) NAMED TEST: name the test method (or class) the diff must add
  #     and pass. The test name carries the behavioral assertion.
  #   - InviteCommandHandlerTest.createConsumesPerAdapterCap passes
  #   - BanCommandHandlerTest.bansKnownContactAndRevokesPendingInvites passes
  #   - mvn -pl infochat-provider verify is green
  #
  # (b) PROSE BEHAVIORAL ASSERTION: a one-line statement the reviewer
  #     can check against the diff or the test output.
  #   - `/invite create` rejects banned target contacts with the audit
  #     row tagged INVITE_REJECTED_BANNED_TARGET
  #   - Flyway migration V<NNN>__<name>.sql applies cleanly on a fresh DB
  #
  # Avoid grep-cardinality acceptance ("grep -cE '...' returns ≥N matches").
  # The test passing is the only ground truth; counting regex matches
  # masks per-element regressions and creates an authoring vocabulary that
  # does not measure behavior. If you need to pin a specific identifier,
  # name the test that exercises it.
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

decomposed_from: M<N>-XXX      # the parent ticket this was split out from
replaces: M<N>-XXX             # the prior ticket this rewrites (refine path resulted in a new ticket)
replaced_by: M<N>-AAA          # set on the OLD ticket when refine produces a new one
deferred_on: M<N>-XXX          # the blocker ticket this is paused on (if status: deferred)
deferred_reason:               # one of: decomposed | blocked-on-new-ticket | spec-amend
spec_amend_for: docs/spec/X.md §Y    # set when this ticket exists to amend the cited spec section
spec_amend_parent: M<N>-XXX    # the implementation ticket waiting on this amendment to land
remediates: M<N>-XXX           # set on a remediation ticket created from a /redteam finding on a done ticket
                               # (the original done ticket is NEVER amended; the new ticket carries the fix)

# --- Dynamic fields (populated by the milestone-driver skill; start empty) -------
#
# `reviews` and `clarity_check` carry the LATEST entry only — git log is
# the audit trail for prior rounds. `escalations` and `revisions` are NOT
# in the schema; refine/escalation history lives in git commit messages
# (`M<N>-NNN: refine ticket spec (<reason>-rework)`).

reviews: {}                    # latest review only: {round, date, verdict, checks, diff_stats}
overrides: []                  # populated on `override` escalations
aborted_attempts: []           # populated by `abort`; one entry per aborted attempt
reopens: []                    # populated by `reopen`; one entry per reopen
redteam_findings: []           # populated by /redteam; one entry per finding
clarity_check: {}              # populated by `start` when ticket-clarity pre-flight runs (LATEST only)
---

# M<N>-NNN: <title>

## Context

One paragraph. Why does this ticket exist? What does completing it
unlock? Cite the parent milestone goal in `docs/plan/<milestone>/README.md`
(e.g. `docs/plan/m1/README.md` §M1 milestone goal) if applicable. Cite the
relevant `spec_refs:` entry as the contract.

## Acceptance

The behavioral contract this ticket commits to. Each item is either a
named test the diff must add and pass, a prose behavioral assertion the
reviewer can check, or a runnable command (e.g. `mvn verify` is green).
Mirror the YAML `acceptance:` list here in prose so a human reading the
ticket understands "done" without parsing YAML — but do not duplicate
behavioral commitments across multiple body sections; this is the only
section that pins behavior. The §Notes section below is rationale and
non-binding.

## Out-of-scope

Prose explanation of what `out_of_scope` covers and why. The reviewer
uses this to judge scope-drift. Be specific about what is *intentionally*
not done here so the developer doesn't accidentally do it. If this
ticket modifies a pre-existing test, name it here with the new expected
behavior — unauthorized test edits are test-integrity violations per
`docs/process/engineering-rules-verbatim.md` §8.

## Notes

Free-form, non-binding rationale, design pointers, alternatives
considered, big-picture cross-cutting concerns the implementer should
keep in mind. The reviewer does not police this section against
acceptance — it is context for the implementer, not commitment. Keep it
brief; if a sentence here describes behavior the diff must implement,
move it to §Acceptance.

- Relevant design note: `docs/design/<NN>-<name>.md` §<section>
- Adjacent code: `<path>` (the existing pattern this should match)
- Alternatives considered, future-shape concerns, anything subtle

## Pre-flight self-check (author-side)

Before committing a new or revised ticket — and BEFORE running
`/m1-tick start <id>` — run `scripts/lint-ticket.py` against the
ticket file. The linter catches a small set of mechanical authoring
errors at author-time so they don't cost a clarity-subagent round.

```bash
python3 scripts/lint-ticket.py docs/plan/<milestone>/tickets/M<N>-NNN-*.md
```

| Check | Catches |
|---|---|
| **FILES-SCOPE-COVERAGE** | `test_plan.adds` / `test_plan.modifies` paths missing from `files_scope`. Either add to `files_scope` (with a `files_budget` bump if needed) or rely on `files_budget` alone (omit `files_scope`). |
| **PROSE-VERB-IN-VERIFY** | Acceptance items using "by reading", "by inspection", "should be present", "loop exits" — not mechanically checkable. Rewrite as a named test or a runnable command. |
