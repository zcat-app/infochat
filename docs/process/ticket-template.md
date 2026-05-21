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
                               # LIFECYCLE-PATH EXEMPTION: do NOT count `docs/plan/m1/STATUS.md` or
                               # this ticket's own file in files_budget. /m1-tick writes them
                               # automatically as part of the workflow; the reviewer auto-exempts
                               # both from the budget and the files_scope membership check
                               # (see docs/process/reviewer-prompt.md §Files budget and scope).
files_scope:                   # OPTIONAL path/glob list. When present, the reviewer ALSO performs
                               # the negative-space check (files in scope NOT touched are surfaced
                               # as PASS or WARN). Omit when the budget is purely numeric.
                               # LIFECYCLE-PATH EXEMPTION: do NOT list `docs/plan/m1/STATUS.md` or
                               # this ticket's own file here; the reviewer treats them as implicitly
                               # in-scope. List only the implementation paths.
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
  #
  # FORWARD-REFERENCE RULE: ticket-ID references in this list (and anywhere else
  # in the ticket — frontmatter or body) are validated by the clarity pre-flight
  # (see docs/process/clarity-prompt.md §FORWARD-REFERENCE-CHECK). A reference
  # to a ticket that does not yet exist as a file under docs/plan/<milestone>/
  # tickets/ produces a clarity WARN here in prose, or a clarity FAIL when the
  # reference is in a load-bearing frontmatter field (blocked_by, deferred_on,
  # decomposed_from, replaces, replaced_by, spec_amend_parent, remediates).
  # File the follow-up ticket as a skeleton before deferring work to it.
acceptance:
  # Ideally runnable assertions, not prose. The reviewer will check
  # each one literally. Examples:
  # - "mvn -pl <module> test -Dtest=<TestName> returns success"
  # - "Flyway migration V<NNN>__<name>.sql applies cleanly on a fresh DB"
  # - "grep -rn '<forbidden-pattern>' src/ returns zero matches"
  #
  # AUTHORING RULE — one assertion = one claim about one named element.
  # When a structural commitment (PK shape, column type, GRANT shape,
  # index definition) spans multiple tables/elements, write ONE
  # acceptance item PER element, each pinning that element by name. Do
  # NOT aggregate via a count across heterogeneous elements. The
  # aggregate-count pattern hides shape differences and silently
  # accepts regressions in any one element so long as the total
  # count is satisfied. Per-element assertions are independently
  # falsifiable and force you to enumerate the elements at
  # authoring time — exactly the structural check that surfaces
  # whether the elements actually share the shape you assumed.
  #
  # ❌ Aggregate-count smell:
  #   - "grep 'PRIMARY KEY\s*\(\s*scope_kind\s*,\s*scope_id\s*,' returns ≥3 matches"
  #     (assumes three tables share a 3-column PK shape; M1-008c hit a
  #      trap here because scope_preferences's PK is 2-column.)
  #
  # ✓ Per-element pattern:
  #   - "source_subscription declares PRIMARY KEY (scope_kind, scope_id, source_id)"
  #   - "scope_tag declares PRIMARY KEY (scope_kind, scope_id, tag_id)"
  #   - "scope_preferences declares PRIMARY KEY (scope_kind, scope_id)"
  #
  # Aggregate counts have one legitimate use: enforcing "exactly N and
  # no more" when all N are structurally identical (e.g., the seven
  # per-stage BOOLEAN flags on `post` — same shape, count is the
  # load-bearing assertion). For heterogeneous element sets, always
  # split. See [[regex-test-vectors]] in author-memory.
test_plan:
  adds:
    # - <module>/src/test/java/.../FooIT.java
  preserves:
    - all tests currently green on main
verified_stays_green:
  # - test_class: <fully-qualified test class name>
  #   rationale: <one-line WHY the test stays green>
  #
  # OPTIONAL list. Required (the author-side linter enforces this as a
  # BLOCKER) when `files_scope` includes a file matching one of the
  # shared-dispatch-surface heuristics (`InboundRouter.java`,
  # `RateCapBucket.java`, `InviteCodeConsumer.java`, `BanCheck.java`,
  # `AutoRegisterService.java`, plus `*Command*.java` under
  # `provider/src/main/java/`). For each out-of-scope test class whose
  # "stays green unchanged" claim depends on the changed dispatch
  # surface, list one entry pinning the test by fully-qualified class
  # name plus a one-line rationale explaining WHY it stays green (e.g.
  # "pre-seeds users via @BeforeEach so the auto-register branch is
  # never exercised").
  #
  # Three pipeline layers consume this field. lint-ticket.py's
  # OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE check (BLOCKER) fires when the
  # heuristic matches and the field is empty/missing — pure authoring
  # forcing function. The clarity reviewer's VERIFIED-STAYS-GREEN-
  # PLAUSIBLE check (WARN) judges each rationale against the cited
  # test source. Plan's dependent-test-coverage audit (in
  # docs/process/plan-prompt.md §Test-scaffolding plan) FAILs the
  # outline if any test under `provider/src/test/` exercising the
  # changed surface is missing from this list, or if a listed test
  # actually needs editing rather than staying green.
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

## Pre-flight self-check (author-side)

Before committing a new or revised ticket — and BEFORE running
`/m1-tick start <id>` — run `scripts/lint-ticket.py` against the
ticket file. The linter encodes the recurring clarity-check failure
patterns from M1 (see `MEMORY.md`); catching them at author-time
avoids paying ~4 minutes of clarity-subagent time per defect.

```bash
python3 scripts/lint-ticket.py docs/plan/<milestone>/tickets/M<N>-NNN-*.md
```

The linter runs seven static checks. If any reports BLOCKER, fix
the ticket BEFORE `/m1-tick start`. WARN findings are advisory but
worth resolving — every unresolved WARN turns into a clarity-check
WARN later.

| Check | Catches |
|---|---|
| **GREP-SHELL-PARSEABLE** | `grep` commands with shell-syntax errors. |
| **GREP-EMBEDDED-QUOTE** | The `''<word>''` smell — author tries to embed a literal apostrophe inside a single-quoted bash regex; bash silently strips the apostrophes via empty-string concat. Use `'\''` or switch the outer delimiter to `"…"`. |
| **REGEX-COMPILABLE** | Malformed regex (unbalanced brackets, missing `)`, etc.). |
| **GREP-CROSS-LINE-NEWLINE** | `\n` inside a `grep -E` pattern run without `-z` / `-P`. GNU grep is line-oriented; `\n` in `-E` never matches a real line boundary. Use single-line patterns like `grep -iE 'void\s+\w*<name>\w*\s*\(' TestFile.java` to assert "a test method named with substring `<name>` exists". |
| **FILES-SCOPE-COVERAGE** | (a) `test_plan.adds` / `test_plan.modifies` paths missing from `files_scope`; (b) code files mentioned in §Implementation notes / §Authorized test changes / §Big-picture notes but not in `files_scope`. Either add the file to `files_scope` (with a `files_budget` bump if needed) or insert an explicit "inner class of X" disclaimer in the section that mentions it. |
| **HETEROGENEOUS-AGGREGATE-COUNT** | Aggregate count ≥N (N ≥ 3) over a grep predicate exhibiting any "collapse" signal: `@Test` pattern (any anchor variant), two or more `.java` paths in one backtick block, or an `awk`-sum pipe (`| awk '{s+=…}'`) following the grep. Each signal hides per-element regressions in the aggregate. See [[no-heterogeneous-aggregate-test-counts]] in author-memory; the fix is one assertion per named element (test method, file, etc.). |
| **PROSE-VERB-IN-VERIFY** | Verify clauses using "by reading", "by inspection", "should be present", or "loop exits" — not mechanically checkable. Rewrite as `grep` / `mvn test -Dtest=...` / etc. |
| **IMPLEMENTATION-NOTES-ACCEPTANCE-CROSS-REF** | Body claims "an acceptance grep confirms X" but X has no matching acceptance item. Either add the acceptance item or remove the claim. |
| **OUT-OF-SCOPE-STAYS-GREEN-VERIFIABLE** | `files_scope` includes a shared-dispatch-surface file (`InboundRouter.java`, `RateCapBucket.java`, `InviteCodeConsumer.java`, `BanCheck.java`, `AutoRegisterService.java`, or `*Command*.java` under `provider/src/main/java/`) but `verified_stays_green:` is empty/missing. The author must enumerate the out-of-scope test classes whose "stays green unchanged" claim depends on the changed dispatch surface, each with a one-line rationale. Catches the M1-044b round-1 shape: 7 AddSource* tests broke because the "stays green" claim was asserted, not audited. |

### Authoring conventions that prevent the most common findings

- **Always wrap `grep` commands in backticks inside acceptance items.** The linter only extracts greps from backticked spans.
- **Prefer double-quoted outer delimiters** in bash greps when the regex must contain a literal apostrophe: `grep -E "UPDATE … SET status = 'USED'" File.java`. This sidesteps the GREP-EMBEDDED-QUOTE class entirely. If you must use single-quoted outer delimiters, embed apostrophes via `'\''`.
- **One assertion = one claim about one named element.** Heterogeneous-aggregate counts hide regressions; per-element greps force you to enumerate the elements at authoring time. See [[regex-test-vectors]].
- **Every test file in `test_plan.adds` or `test_plan.modifies` belongs in `files_scope`** (so the reviewer's negative-space check covers it). If `files_scope` is intentionally narrow, omit it and rely on `files_budget` alone.
- **If §Implementation notes prescribes an artifact (a CDI producer, a test helper, a properties file) that isn't in `files_scope`**, either add it to `files_scope` or add an explicit "implemented as an inner class of X" / "configured via constructor `defaultValue`" disclaimer in the section that mentions it.
