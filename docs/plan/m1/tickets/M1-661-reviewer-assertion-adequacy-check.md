---
id: M1-661
title: "Add an ASSERTION-ADEQUACY check to the reviewer gate"
status: pending
created: 2026-07-19
last_updated: 2026-07-19
blocked_by: []
files_budget: 4
files_scope:
  - docs/process/reviewer-prompt.md
  - docs/process/engineering-rules-verbatim.md
  - .claude/skills/m1-tick/subcommands/review.md
  - docs/process/workflow.md
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
  - >-
    docs/process/reviewer-prompt.md §"Skill responsibilities" describes the
    diff capture as `git diff $(git merge-base main HEAD)` in BOTH step 2
    (full diff) and step 3 (`--shortstat`), matching what review.md step 1
    actually does. No `git diff main` wording survives in that section. The
    adjacent rationale sentence ("a commit-range diff against `main` would be
    empty here because `commit` runs after `review`") stays as-is — it is
    still true.
  - >-
    docs/process/workflow.md §"4. Review" describes the diff the reviewer
    receives as `git diff $(git merge-base main HEAD)` (working-tree-vs-fork-
    point), not `git diff main` / "working-tree-vs-main". No `git diff main`
    wording survives anywhere in workflow.md. The existing rationale clause
    ("a commit-range diff against `main` would be empty" because `commit`
    runs after `review`) and the adjacent `git add -N` sentence both stay —
    they are still true. This is the same defect as the preceding item at the
    second of its two live sites; workflow.md self-declares as "the single
    source of truth for the procedure" (workflow.md:3), so leaving it stale
    is the more load-bearing half of the pair.
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
| `docs/process/workflow.md` | out-of-scope **for this class**: its table maps FRONTMATTER FIELDS to enforcing checks. The new check derives from the diff, not from a frontmatter field, so it has no row to add. The file is nonetheless in `files_scope` — it enters via the *second* deliverable below, which is a different defect class (a stale diff-capture description, not a missing check registration) |

## Second deliverable: the two stale diff-capture descriptions

`reviewer-prompt.md` §"Skill responsibilities" steps 2 and 3 say the skill
captures `git diff main`. It does not: `review.md` step 1 uses `git diff
$(git merge-base main HEAD)` and explicitly warns *against* diffing on
`main`, because in a worktree pinned behind a moved `main` that drags every
since-landed ticket into the review as phantom changes (observed M1-096,
2026-05-30). The skill file is operative, so live behavior is correct and
this is a documentation defect — but it is the kind that reintroduces the bug
the moment anyone re-derives the procedure from the description.

`reviewer-prompt.md` is not the only site. `workflow.md:163` §"4. Review"
describes the same capture as "`git diff main`, working-tree-vs-main" with
the same "would be empty" rationale, and `grep merge-base
docs/process/workflow.md` returns zero hits — nothing elsewhere in that file
corrects it. Both sites are fixed here.

Enumerated, so the claim is checkable rather than impressionistic: `grep -rn
'git diff main' --include=*.md .`, excluding `docs/plan/m1/tickets/` and
`docs/plan/m1/redteam/` (the same historical-record exclusion §Census
applies), returns four paths. Two are **correct** — `review.md:15` and
`redteam/SKILL.md:56` carry the string only inside an explicit negative
callout ("**Not** `git diff main`"). Two are **wrong** and are this
deliverable's scope: `reviewer-prompt.md:342-343` and `workflow.md:163`.

Fixing both rather than one is what the ticket's own rationale demands. The
defect matters because a wrong description reintroduces the bug when someone
re-derives the procedure from it — and `workflow.md:3` self-declares as "the
single source of truth for the procedure", i.e. it is precisely the document
one re-derives from. Repairing the subordinate description while leaving the
authoritative one stale would fix the weaker half. (An earlier draft of this
ticket asserted `reviewer-prompt.md` was "the odd one out of three" and the
only wrong description; the clarity pre-flight falsified that, which is why
`workflow.md` is now in `files_scope` at `files_budget: 4`.)

Bundling is explicit, not incidental — each site carries its own acceptance
item, so every changed line traces to the contract rather than reading as
"while we were in there".

## Acceptance

Mirrors the YAML list above. In prose: `reviewer-prompt.md` gains
ASSERTION-ADEQUACY-CHECK over the domain PASS | WARN | FAIL |
NOT-APPLICABLE, asking boundary-siting and non-vacuity; a FAIL must name the
surviving mutation or unasserted boundary with a file:line, so the check
cannot block on impression; a diff adding no new test reports
NOT-APPLICABLE; the definition disclaims §8 Semantic and clarity check 10 by
name; §Verdict rules pins WARN as non-blocking and FAIL as at-least-REWORK;
`engineering-rules-verbatim.md` §8 carries the canonical text;
`review.md` step 4 registers the name so the skill extracts it;
§"Skill responsibilities" steps 2 and 3 describe the merge-base diff capture
with no `git diff main` wording left in that section; and `workflow.md`
§"4. Review" does the same, leaving no `git diff main` wording anywhere in
that file.

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
ticket passes through, while `complexity: low` reflects the four-file
documentation diff. The FAIL-must-name-an-artifact rule is the guard against
the failure mode a new blocking check usually has — noise on the 88% of
tickets that are fine, followed by the check being overridden into
irrelevance.
