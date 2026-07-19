---
id: M1-661
title: "Add an ASSERTION-ADEQUACY check to the reviewer gate"
status: pending
created: 2026-07-19
last_updated: 2026-07-19
blocked_by: []
files_budget: 3
files_scope:
  - docs/process/reviewer-prompt.md
  - docs/process/engineering-rules-verbatim.md
  - .claude/skills/m1-tick/subcommands/review.md
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Mutation-testing infrastructure (PIT or equivalent). Considered and
    rejected for v1: it would catch the M1-651 truncation mechanically but
    NOT the M1-648 defect, because mutating HelpLookupTool is caught by
    HelpLookupToolIT — the defect was a downstream consumer outside the
    ticket's test scope. It also carries heavy runtime cost on a
    Testcontainers-backed suite. If wanted, it is its own ticket.
  - >-
    Changing TEST-INTEGRITY-CHECK or any existing rule under
    engineering-rules-verbatim.md §8. The new check is additive and sits
    beside them; the existing semantic rules keep sole ownership of
    MODIFICATIONS to pre-existing tests.
  - >-
    Anything the clarity gate's check 10 (CLASS-COMPLETENESS) already owns.
    That check asks whether a class-scoped ticket ENUMERATED its class; this
    one asks whether the tests CONSTRAIN what the diff claims. They must not
    overlap, and this ticket does not touch docs/process/clarity-prompt.md.
  - >-
    Re-auditing already-merged tickets against the new check. It applies to
    diffs reviewed after it lands; M1-648 and M1-651 are historical evidence
    for why it exists, not work items.
  - >-
    Any change under a module src/ tree, any *.java file, pom.xml, or
    src/**/resources/**. This ticket is documentation and skill-procedure
    only.
acceptance:
  - >-
    docs/process/reviewer-prompt.md defines ASSERTION-ADEQUACY-CHECK with the
    verdict domain PASS | WARN | FAIL | NOT-APPLICABLE, and the check appears
    as its own entry in the §"On-disk verdict format" block alongside the
    existing five checks.
  - >-
    The check asks exactly two questions: (a) BOUNDARY SITING — for a value
    the diff introduces that reaches a user or an external surface, does at
    least one assertion live at the END of that path rather than only at the
    point of production; (b) NON-VACUITY — for each NEW test the diff adds,
    can a concrete mutation of the diff's own production code be named that
    the test would catch.
  - >-
    FAIL requires the reviewer to NAME the specific surviving mutation or the
    specific unasserted boundary, with a file:line. A FAIL that cannot name
    one is not a valid FAIL. This is stated in the check's definition.
  - >-
    The check reports NOT-APPLICABLE, with a one-line reason, when the diff
    adds no new test — so documentation-only and process-only diffs pass
    through without noise.
  - >-
    The definition explicitly disclaims its two neighbours: modifications to
    pre-existing tests remain TEST-INTEGRITY-CHECK's territory per
    engineering-rules-verbatim.md §8 Semantic, and class enumeration remains
    clarity check 10's.
  - >-
    §"Verdict rules" in reviewer-prompt.md states the APPROVE gating: WARN
    does not block APPROVE (it surfaces informationally, as NEGATIVE-SPACE
    WARN does), FAIL forces at least REWORK.
  - >-
    docs/process/engineering-rules-verbatim.md §8 gains a subsection carrying
    the canonical rule text, and the reviewer-prompt definition cites it
    rather than restating it.
  - >-
    .claude/skills/m1-tick/subcommands/review.md step 4's per-check extraction
    list names ASSERTION-ADEQUACY-CHECK. Without this the skill parses five
    check names out of the verdict file and the sixth is silently dropped.
test_plan:
  adds: []
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

# M1-661: Add an ASSERTION-ADEQUACY check to the reviewer gate

## Context

The reviewer takes the test suite as its oracle. `reviewer-prompt.md`
§Acceptance says of each acceptance item: "the test log either confirms or
denies each." Nothing in the gate asks whether the tests themselves
constrain the claim, so a diff whose tests assert at the wrong layer passes
every check while the user-visible behavior is wrong.

Two merged tickets demonstrate the gap. **M1-648**: `HelpLookupToolIT`
asserted on the tool's return value, which is pre-sanitizer;
`LlmOutputSanitizer` then redacted every command the tool usefully resolved,
so the ticket's flagship scenario produced `Use [redacted command] <id>` for
ordinary DM users. Clarity, review and redteam were all green — no gate
traced the value past the tool boundary, because no test did. **M1-651(a)**:
the shipped guard's only completeness check was non-emptiness, so a blank
line mid-bullet silently cut the guarded set from 19 commands to 8 and the
guard still passed; the red-team reproduced it mechanically.

This is a real hole and not a duplicate of an existing gate. §8 Semantic
covers only *modifications to pre-existing* tests — weakened assertions,
mocks replacing real wiring, a test changed to match wrong behavior. §8
Syntactic covers `assertTrue(true)`-shaped trivialities, the crudest vacuity
only. Neither reaches a newly-added test that asserts honestly but in the
wrong place.

## Census

The class is "files that must know a reviewer check name for a new check to
be wired consistently". Enumerated mechanically:

    grep -rln "TEST-INTEGRITY-CHECK\|SCOPE-DRIFT-CHECK\|SPEC-CONFORMANCE-CHECK" \
      --include=*.md .

Excluding `docs/plan/m1/tickets/` and `docs/plan/m1/redteam/` (per-ticket
records that cite a verdict they received; they are history, not wiring),
that returns seven paths:

| Site | Disposition |
|---|---|
| `docs/process/reviewer-prompt.md` | fix — defines every check and the verdict block |
| `docs/process/engineering-rules-verbatim.md` | fix — canonical §8 rule text the check cites |
| `.claude/skills/m1-tick/subcommands/review.md` | fix — step 4 ENUMERATES the five names the skill extracts from the verdict file; an unregistered sixth is parsed by nothing |
| `.claude/skills/m1-tick/SKILL.md` | out-of-scope: single SCOPE-DRIFT-CHECK reference inside the must-shrink rule, not a check registry |
| `.claude/skills/m1-tick/subcommands/escalate.md` | out-of-scope: names TEST-INTEGRITY-CHECK only, for the override restriction. The new check carries no override restriction, so no row is owed |
| `docs/process/ticket-template.md` | out-of-scope: one SCOPE-DRIFT-CHECK mention in a `files_budget` comment |
| `docs/process/workflow.md` | out-of-scope: its table maps FRONTMATTER FIELDS to enforcing checks. The new check derives from the diff, not from a frontmatter field, so it has no row to add |

## Acceptance

Mirrors the YAML list above. In prose: `reviewer-prompt.md` gains
ASSERTION-ADEQUACY-CHECK over the domain PASS | WARN | FAIL |
NOT-APPLICABLE, asking boundary-siting and non-vacuity; a FAIL must name the
surviving mutation or unasserted boundary with a file:line, so the check
cannot block on impression; a diff adding no new test reports
NOT-APPLICABLE; the definition disclaims §8 Semantic and clarity check 10 by
name; §Verdict rules pins WARN as non-blocking and FAIL as at-least-REWORK;
`engineering-rules-verbatim.md` §8 carries the canonical text; and
`review.md` step 4 registers the name so the skill extracts it.

## Out-of-scope

Covered in the YAML above. The load-bearing exclusions: **PIT / mutation
testing** is the mechanical alternative and was rejected on evidence, not
taste — it would have caught M1-651(a) but not M1-648, because the M1-648
mutation lives in a consumer the ticket's tests never touched. **The clarity
gate is not modified**; check 10 already covers class enumeration, and
M1-651(b) — the unguarded group-admin half — is *its* catch, not this
check's. Claiming it here would double-count. **No test files are modified**,
so §8's test-modification authorization rule is not engaged.

## Notes

Non-binding rationale.

`mvn verify` is **N/A for this ticket**, and that is the documented path, not
an exception: the diff touches none of `*.java`, `pom.xml`, or
`src/**/resources/**`, which makes it a *fully inert* diff under the
inert-diff gate stated in the m1-tick skill's workflow rules ("`mvn verify`
scope — Java/config/DB only"); that skill file is cited for the rule, never
modified, and is dispositioned out-of-scope in §Census.
`review.md` step 0 already accepts a fully-inert diff with the
inert-N/A round-log note and no prior green log. Record the note; do not run
the suite to produce a green tick that covers nothing in the diff.

On expected yield, stated honestly so nobody over-promises for it: across the
M1 corpus the reviewer caught a genuine correctness or security defect in
roughly 3 of 83 rework rounds — it is weak at open-ended bug hunting. The
three it did catch (M1-066 audit-before-effect ordering, M1-061 Flyway
version collision, M1-583 a deleted eligibility block) share a shape: a
concrete, bounded question with a checkable answer. "Name a mutation this
test would catch" is that shape, which is why the check is written as a
named-artifact demand rather than an invitation to look for problems. It
raises detection probability; it does not guarantee it.

`risk: medium` because this changes a gate contract that every subsequent
ticket passes through, while `complexity: low` reflects the three-file
documentation diff. The FAIL-must-name-an-artifact rule is the guard against
the failure mode a new blocking check usually has — noise on the 88% of
tickets that are fine, followed by the check being overridden into
irrelevance.
