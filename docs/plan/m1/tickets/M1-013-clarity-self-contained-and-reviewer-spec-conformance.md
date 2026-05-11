---
id: M1-013
title: Clarity self-contained check and reviewer spec-conformance check
status: pending
created: 2026-05-11
last_updated: 2026-05-11

blocked_by:
  - M1-010
files_budget: 4
files_scope:
  - docs/process/clarity-prompt.md
  - docs/process/reviewer-prompt.md
  - .claude/agents/clarity-reviewer.md
  - .claude/agents/code-reviewer.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any edit to docs/process/engineering-rules-verbatim.md (rules-of-record stay untouched; this ticket adds review dimensions, not rules)
  - any edit to docs/process/ticket-template.md (ticket frontmatter shape is unchanged — spec_refs already exists, no new field is added; the new checks operate on existing data)
  - any edit to docs/process/workflow.md (the workflow doc describes WHAT the reviewers receive and verdicts they return; the new checks slot in as additional `*-CHECK` lines under the existing verdict shape and require no workflow-doc change)
  - any edit to docs/process/plan-prompt.md or docs/process/redteam-prompt.md (Plan subagent fires only on complexity:high tickets; threat-actor is a separate `/redteam` skill; this ticket does NOT add equivalent checks to those flows)
  - any edit to .claude/agents/threat-actor.md or .claude/agents/status-regenerator.md (orthogonal agents; not affected by clarity/review semantics)
  - any edit to .claude/skills/m1-tick/SKILL.md or .claude/skills/m1-tick/subcommands/** (the new checks are implemented inside the prompt-template + agent-persona files; the skill's substitution wiring is unchanged — the same `{{TICKET_FILE_PATH}}` and `{{SPEC_REFS_LIST}}` payload M1-010 already provides is enough, with no new placeholder)
  - any edit to .claude/skills/redteam/
  - any edit to other ticket files under docs/plan/m1/tickets/ (M1-001..M1-012 stay untouched)
  - any change to repo source code, poms, application.properties, migrations, or test code (this ticket only edits process docs and agent definitions)
  - any new Maven module or pom change of any kind
  - any change to the existing CLARITY VERDICT semantics (PASS | WARN | FAIL) or VERDICT semantics (APPROVE | REWORK | MANUAL | OVERRIDE-APPROVE) — only the per-check list grows, the top-level verdicts and verdict-derivation rules are byte-identical
  - any change to STATUS.md content beyond what the regenerator emits
  - any change to round-cap rules, must-shrink rules, or escalation triggers
acceptance:
  - "within docs/process/clarity-prompt.md, the `## What to check` block (bounded by `## What to check` and `## Short chat reply` headings) introduces a new numbered SELF-CONTAINED check. Verify: grep -nE '^### [0-9]+\\. ' docs/process/clarity-prompt.md returns one more match than the pre-edit file AND grep -nF 'SELF-CONTAINED' docs/process/clarity-prompt.md returns at least one match in that block"
  - "within docs/process/clarity-prompt.md, the verdict-format block (bounded by `## On-disk verdict format` and `## Verdict rules` headings) adds a `SELF-CONTAINED-CHECK: <PASS | WARN | FAIL>` line. Verify: grep -nE '^SELF-CONTAINED-CHECK:' docs/process/clarity-prompt.md returns at least one match"
  - "within docs/process/clarity-prompt.md, the SELF-CONTAINED check body describes the load-bearing-vs-supplementary spec_refs distinction. Verify: grep -niE 'load.bearing|supplementary|inline.*spec' docs/process/clarity-prompt.md returns at least one match within the `## What to check` block"
  - "within docs/process/reviewer-prompt.md, the `## On-disk verdict format` block (bounded by `## On-disk verdict format` and `## Verdict rules` headings) adds a `SPEC-CONFORMANCE-CHECK: <PASS | WARN | FAIL>` line. Verify: grep -nE '^SPEC-CONFORMANCE-CHECK:' docs/process/reviewer-prompt.md returns at least one match"
  - "within docs/process/reviewer-prompt.md, the rules section above the verdict block explicitly instructs the reviewer to Read every spec_refs file before judging conformance. Verify: grep -niE 'Read.*spec_refs|each spec_ref.*Read' docs/process/reviewer-prompt.md returns at least one match"
  - "within docs/process/reviewer-prompt.md, the verdict-rules section names SPEC-CONFORMANCE-CHECK in the list of checks that can force REWORK. Verify: grep -niE 'SPEC-CONFORMANCE-CHECK.*FAIL|any .*-CHECK.*FAIL' docs/process/reviewer-prompt.md returns at least one match in the `## Verdict rules` block (the existing 'Any *-CHECK: FAIL forces VERDICT to be at least REWORK' line already covers SPEC-CONFORMANCE-CHECK because it matches the *-CHECK pattern; no new derivation rule is needed but the check must be enumerated as a *-CHECK)"
  - ".claude/agents/clarity-reviewer.md persona body (post-frontmatter) describes the new SELF-CONTAINED check. Verify: grep -niE 'self.contained|load.bearing' .claude/agents/clarity-reviewer.md returns at least one match after the YAML frontmatter"
  - ".claude/agents/code-reviewer.md persona body (post-frontmatter) describes the new SPEC-CONFORMANCE check including the instruction to Read each spec_refs file before judging. Verify: grep -niE 'spec.conformance|spec_refs.*Read|Read.*spec_refs' .claude/agents/code-reviewer.md returns at least one match after the YAML frontmatter"
  - "the structural contract for SPEC-CONFORMANCE-CHECK is documented in docs/process/reviewer-prompt.md as: reviewer Reads every spec_refs entry in the ticket's frontmatter (in its fresh context — does NOT leak spec bytes to main session), compares the diff against the cited spec sections, and flags semantic mismatches between what the spec promises and what the diff delivers. Verify by inspection: the SPEC-CONFORMANCE-CHECK paragraph in the verdict block instructs the reviewer to (a) Read each cited spec file by anchor range (see next item), (b) compare diff semantics to spec semantics, (c) FAIL on a clear mismatch / WARN on partial coverage / PASS when the diff faithfully implements the cited sections."
  - "the SPEC-CONFORMANCE-CHECK contract instructs the reviewer to Read each spec_refs entry by ANCHOR RANGE — locate the cited heading using the same anchor-resolution algorithm `docs/process/clarity-prompt.md` already documents, then Read from the heading line until the next heading at the same-or-higher depth — NOT the whole file. Verify: grep -niE 'anchor range|by anchor|until the next heading|same.or.higher' docs/process/reviewer-prompt.md returns at least one match in the SPEC-CONFORMANCE paragraph; AND the algorithm itself is cross-referenced rather than duplicated (grep -nF 'clarity-prompt.md' docs/process/reviewer-prompt.md returns at least one match in the SPEC-CONFORMANCE paragraph so the reviewer reuses the existing anchor-resolution algorithm)."
  - "the structural contract for SELF-CONTAINED-CHECK is documented in docs/process/clarity-prompt.md as: clarity-reviewer judges whether the ticket body carries enough behavioral detail that an implementer doesn't need to Read cited spec files to know WHAT to build (cited spec files may still be used for cross-reference / context; they must not be load-bearing). Verify by inspection: the SELF-CONTAINED paragraph in the `## What to check` block names concrete failure shapes (e.g. acceptance items that say 'implements §X of the spec' without inlining the behavioral assertion; Definition of Done that delegates to spec without inlining the SPI shape)."
  - "the new clarity check leans WARN over FAIL on judgment-call cases. Verify by inspection: the SELF-CONTAINED paragraph in docs/process/clarity-prompt.md explicitly documents the WARN-over-FAIL default (mirrors the FILES-BUDGET-PLAUSIBLE precedent at line ~73 of the pre-edit file: 'You can be wrong; lean to WARN over FAIL on this dimension')."
  - "no new placeholders are introduced in either prompt template. Verify: grep -nE '\\{\\{[A-Z_]+\\}\\}' docs/process/clarity-prompt.md returns the same set of placeholder names as before this ticket (no additions beyond what M1-010 establishes); same for docs/process/reviewer-prompt.md. The new checks operate on existing prompt content (the ticket file the subagent Reads, the spec_refs list in the ticket frontmatter)."
  - "mvn -B verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (this ticket only edits process docs and agent definitions; mvn verify is a smoke check that no source code was perturbed)
spec_refs:
  - docs/process/clarity-prompt.md §What to check
  - docs/process/clarity-prompt.md §On-disk verdict format
  - docs/process/clarity-prompt.md §Verdict rules
  - docs/process/reviewer-prompt.md §On-disk verdict format
  - docs/process/reviewer-prompt.md §Verdict rules
  - .claude/agents/clarity-reviewer.md §What you check
  - .claude/agents/code-reviewer.md §How you read the prompt
decision_refs: []

reviews: []
escalations:
  - date: 2026-05-11
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        - docs/process/clarity-prompt.md §Return exactly this format → ANCHOR-NOT-FOUND
        - docs/process/reviewer-prompt.md §Your verdict → ANCHOR-NOT-FOUND
      (Both anchors missing from their target files; see clarity_check.blockers for fix suggestions.)
revisions:
  - date: 2026-05-11
    reason: clarity-fail rework — fix non-existent heading references
    prior_values: |
      spec_refs (lines 56, 58):
        - docs/process/clarity-prompt.md §Return exactly this format
        - docs/process/reviewer-prompt.md §Your verdict
      acceptance items (lines 36, 37, 39) and Definition of Done (line 182)
      referred to the same non-existent headings in prose.
      The verdict-format block in both files is actually titled
      "## On-disk verdict format"; the block bounding `## What to check`
      in clarity-prompt.md is `## Short chat reply`.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-013: Clarity self-contained check and reviewer spec-conformance check

## Context

M1-010 removes spec content from the main-session transcript at
`/m1-tick start` time: spec_refs resolution moves into the clarity
subagent's fresh context, the engineering-rules block becomes a
Read pointer in the reviewer subagent, and verdicts go to disk
with only short summaries returning to chat. That is the
mechanical half of "main session shouldn't carry the whole
project's worth of spec".

The other half is two semantic checks the existing prompts don't
carry:

1. **The implementer, mid-implementation, still reads spec on
   demand.** After `/m1-tick start` finishes, SKILL.md hands control
   back to the main session as the developer (SKILL.md line ~114).
   If the ticket body is thin — acceptance items like "implements
   §3 of docs/spec/foo.md" without inlining what §3 actually
   requires — the implementer has no choice but to Read the cited
   spec file in main-session context, undoing M1-010's
   spec-out-of-main-session win. The clarity-reviewer already
   checks `spec_refs` resolve to real anchors; it does NOT check
   whether the ticket body carries enough of the cited spec
   sections' content to be implementable without re-reading them.

2. **The reviewer trusts acceptance items as the contract; it
   never cross-checks them against the spec.** ACCEPTANCE-CHECK,
   SCOPE-DRIFT-CHECK, TEST-INTEGRITY-CHECK, OUT-OF-SCOPE-CHECK,
   NEGATIVE-SPACE-CHECK all operate on the ticket+diff pair. None
   of them asks: does the diff actually do what the cited
   `spec_refs` sections require? In principle this is fine — if the
   acceptance items faithfully shadow the spec, then satisfying
   acceptance == satisfying spec. But the user has explicitly
   flagged that the reviewer subagent's fresh context is the
   right place to validate that shadow, not the main session, and
   not nowhere.

This ticket adds:

- **SELF-CONTAINED-CHECK** to the clarity-reviewer's verdict
  schema. The check asks: is the ticket body load-bearing enough
  that an implementer wouldn't need to Read cited spec files in
  main-session context? The clarity-reviewer has Read/Grep and
  the ticket file path; after M1-010, it also resolves spec_refs
  itself, so it can compare the ticket body against the cited
  spec sections and judge whether the ticket inlines what an
  implementer needs.
- **SPEC-CONFORMANCE-CHECK** to the code-reviewer's verdict
  schema. The check asks: does the diff faithfully implement the
  cited spec sections, beyond just satisfying the acceptance
  items as literal strings? The code-reviewer Reads each
  `spec_refs` file in its fresh context (the same fresh context
  that, post-M1-010, already Reads `engineering-rules-verbatim.md`),
  compares diff semantics to spec semantics, and flags
  mismatches.

Both checks operate on data already in the system — the ticket
frontmatter's `spec_refs:` list. No new placeholder is added to
either prompt template. The substitution wiring M1-010 establishes
is exactly enough: the subagents Read what they need.

This is a semantic addition, not a structural one. M1-010
established WHERE the work happens (subagent fresh context);
M1-011 made the skill's per-invocation load smaller; M1-012
moves STATUS regen out of main session. M1-013 extends the
already-relocated review surface with two new judgments — one in
each subagent — that the user's framing exposed as gaps.

## Definition of Done

- `docs/process/clarity-prompt.md` `## What to check` block adds
  a new numbered section, **SELF-CONTAINED**, after the existing
  seven checks (`ACCEPTANCE-RUNNABLE`, `OUT-OF-SCOPE-SPECIFIC`,
  `SPEC-REFS-VALID`, `FILES-BUDGET-PLAUSIBLE`,
  `COMPLEXITY-RISK-CALIBRATED`, `TEST-CHANGES-AUTHORIZED`,
  `SECURITY-FLAG-CONSISTENT`). The new section describes the
  load-bearing-vs-supplementary distinction for cited spec
  sections, names concrete failure shapes (acceptance items
  that delegate to spec without inlining the behavioral
  assertion; Definition of Done that names an SPI by spec
  section without naming the SPI's shape), and explicitly
  states the WARN-over-FAIL bias for judgment-call cases.
- `docs/process/clarity-prompt.md` `## Return exactly this
  format` block adds a `SELF-CONTAINED-CHECK: <PASS | WARN |
  FAIL>` line in the per-check list, in the same position the
  numbered section appears in `## What to check` (eighth
  check). The line carries the same one-paragraph rationale
  shape as the other checks.
- `docs/process/clarity-prompt.md` `## Verdict rules` block is
  unchanged — the existing rules ("Any *-CHECK: FAIL forces
  CLARITY VERDICT: FAIL") already cover the new check via the
  wildcard pattern.
- `docs/process/reviewer-prompt.md` `## On-disk verdict format` block adds
  a `SPEC-CONFORMANCE-CHECK: <PASS | WARN | FAIL>` line in the
  per-check list, positioned after `ACCEPTANCE-CHECK`. The line
  carries the same one-paragraph rationale shape.
- `docs/process/reviewer-prompt.md` adds an instruction
  paragraph above or near the verdict block telling the
  code-reviewer to Read each `spec_refs:` entry in the ticket's
  frontmatter before judging SPEC-CONFORMANCE-CHECK. The
  paragraph is explicit that this Read happens in the reviewer's
  fresh context (the agent's allowlist already permits Read),
  not in main-session context, so the spec bytes do not leak
  back to main.
- `docs/process/reviewer-prompt.md` `## Verdict rules` block is
  unchanged — the existing rules ("Any *-CHECK: FAIL forces
  VERDICT to be at least REWORK; APPROVE requires every check
  to be PASS") already cover the new check via the wildcard
  pattern.
- `.claude/agents/clarity-reviewer.md` persona body lists the
  new SELF-CONTAINED check alongside the existing seven and
  describes its job in one sentence: judge whether the ticket
  inlines enough behavioral detail that an implementer wouldn't
  need to load cited spec files in main-session context.
- `.claude/agents/code-reviewer.md` persona body lists the new
  SPEC-CONFORMANCE check and describes its contract in one
  sentence: Read each `spec_refs:` file in fresh context,
  compare diff semantics to spec semantics, FAIL on a clear
  mismatch / WARN on partial coverage / PASS when the diff
  faithfully implements the cited sections. The persona
  reaffirms that this Read happens in the reviewer's own
  fresh context.
- No new prompt placeholders. The substitution wiring is
  unchanged. The new checks operate on data already in the
  prompt (the ticket file at `{{TICKET_FILE_PATH}}` after
  M1-010; the spec_refs entries the subagent resolves in fresh
  context).
- `mvn -B verify` from the repo root exits 0.

## Implementation notes

- **Why post-M1-010.** Pre-M1-010 the clarity-reviewer doesn't
  have to Read the ticket itself — `{{TICKET_FILE_CONTENT}}` is
  inlined; same for the cited spec sections (the main session
  resolves them and inlines a `{{SPEC_REF_RESOLUTIONS}}` block).
  Adding SELF-CONTAINED-CHECK there means writing instructions
  that interpret the inlined block. Post-M1-010 the
  clarity-reviewer Reads the ticket file itself and resolves
  spec_refs in its own fresh context, which makes the check a
  natural extension: "you already loaded the ticket and the spec
  sections — judge whether the ticket inlines enough of the
  spec sections' content". The blocked_by reflects this: M1-013
  builds on M1-010's post-state.
- **The SELF-CONTAINED check's failure heuristics.**
  Concrete signals the clarity-reviewer should look for in the
  ticket body:
  - Acceptance items of the form `implements §X of docs/spec/Y.md`
    without an inlined behavioral statement of what §X requires
    → FAIL (the spec text IS the contract; an implementer cannot
    succeed without re-reading it).
  - Acceptance items of the form `the SPI matches docs/spec/Y.md
    §Z` without naming the SPI's methods/types in the ticket →
    FAIL (the SPI shape is load-bearing; inline it).
  - Definition of Done bullets that name a spec concept
    (`per the threat model`, `per the LLM routing rules`)
    without restating the relevant invariant → WARN (the
    implementer might infer it from adjacent code or might
    not — judgment call).
  - Context that cites `spec_refs` purely as cross-reference
    (e.g., `this ticket implements one stage of the pipeline
    described in docs/spec/architecture.md §Ingest pipeline`)
    while the actual implementation contract lives in the
    Definition of Done → PASS (spec_refs are supplementary;
    the ticket body is load-bearing on its own).
  These are heuristics, not bright lines. The check should lean
  WARN over FAIL on ambiguous cases — the precedent is
  FILES-BUDGET-PLAUSIBLE which already documents this bias.
- **The SPEC-CONFORMANCE check's failure heuristics.** The
  code-reviewer should look for:
  - Method/function names in the diff that don't match the
    spec's named SPI methods → FAIL (the spec is the contract;
    the diff renames it without authorization).
  - Behavioral guarantees the spec promises that the diff
    omits (e.g., spec says `the loader is idempotent`; diff
    has no idempotency guard) → FAIL.
  - Spec section names a list of N requirements; diff
    implements only M < N of them, and the ticket's acceptance
    only claimed M → WARN (the ticket's coverage is partial
    relative to the spec section; surfaces to the user as
    informational; does not block APPROVE).
  - Spec section says one thing; diff does that thing plus an
    adjacent thing the spec doesn't mention → this is SCOPE-
    DRIFT-CHECK territory (the existing check covers it), not
    SPEC-CONFORMANCE.
  - Spec section says one thing; diff does that thing faithfully
    → PASS.
  - Ticket cites a `spec_refs` entry the diff is materially
    unrelated to → WARN (the ticket may be over-citing; doesn't
    block APPROVE).
- **No new placeholder.** Both prompt templates already carry
  the ticket file (after M1-010, as `{{TICKET_FILE_PATH}}`).
  The spec_refs list is in the ticket frontmatter the subagent
  Reads. So the new checks have everything they need without
  changing the skill's substitution wiring. This keeps M1-013's
  surface tight — four files, no skill edit.
- **Why FAIL is allowed on SPEC-CONFORMANCE-CHECK** (not just
  WARN). If the diff materially deviates from what the cited
  spec says, that is a real review failure that should force
  REWORK. The existing rule "Any *-CHECK: FAIL forces VERDICT
  to be at least REWORK" applies cleanly. A WARN-only check
  would let the reviewer flag concerns but couldn't block
  APPROVE — that's the wrong shape for "diff diverges from
  spec". The user's framing explicitly says the reviewer
  should "verify the claim" — which means it can fail the
  claim.
- **Why WARN-leaning on SELF-CONTAINED-CHECK.** The judgment
  "is this ticket detailed enough" is fundamentally subjective.
  Some tickets are inherently small and self-contained (a one-
  line config change cites a spec section for context but the
  acceptance is a literal grep); some look thin but the cited
  spec section is tiny too. Forcing FAIL on ambiguous cases
  would block more starts than is warranted. The reviewer
  should FAIL only on clear cases (the load-bearing examples
  above) and WARN otherwise.
- **The reviewer's spec_refs Read cost.** Each cited spec file
  is typically 100–500 lines (a few KB to ~20 KB). Tickets
  cite 2–5 spec sections on average. Whole-file Reads would
  absorb 10–100 KB of spec content per review; anchor-range
  Reads (next bullet) tighten that to 2–20 KB. Either way
  the cost is paid ONCE per review in the subagent's fresh
  context, never leaking to main-session transcript. M1-010
  already established this trade-off explicitly when it
  replaced the 85-line embedded engineering-rules block with
  a Read pointer to a 94-line canonical file. The reviewer's
  fresh context is the right place for this cost.
- **Anchor-range Read, not whole-file Read.** The `spec_refs`
  entries are structured as `<file-path> §<section-title>`,
  which is enough to slice each cited section out of its
  file. The code-reviewer reuses the anchor-resolution
  algorithm `docs/process/clarity-prompt.md` already
  documents (case-insensitive substring match against
  `#`-prefixed headings; ambiguity rules; line-number
  output) to locate the section's starting line, then Reads
  from that line until the next heading at the same-or-higher
  depth. For a citation like `docs/spec/security.md §Per-adapter
  admin threat profile`, the reviewer Reads ~30–80 lines of
  the cited section instead of the file's ~600 lines.
  Cross-reference the algorithm rather than duplicating it
  (clarity-prompt.md is the single source of truth for the
  resolution shape); the code-reviewer's extension is one
  step beyond — bound the body Read by the next same-or-higher
  heading. If a citation's `<section-title>` is missing
  (entry is just `<file-path>` with no `§`), Read the whole
  file as before — the anchor-range tightening only applies
  when a section is named. If anchor resolution returns
  `ANCHOR-NOT-FOUND` or `AMBIGUOUS`, fall back to whole-file
  Read AND raise SPEC-CONFORMANCE-CHECK to WARN with a note
  citing the unresolved anchor — the spec-conformance judgment
  is still made on the available content, but the operator is
  informed that the citation could not be tightened.
- **The clarity-reviewer's spec_refs Read cost.** Same shape:
  the clarity-reviewer already Reads cited spec files (after
  M1-010, to resolve anchors). Re-reading the same files for
  the SELF-CONTAINED check is a no-op cost — the agent
  already has them loaded. The new check just adds a question
  to ask about content it already has.

## Big-picture notes

- **What this does NOT do.** It does not change the workflow
  shape — no new statuses, no new escalation triggers, no new
  five-way menu branch, no new substitution placeholder, no
  ticket-frontmatter schema change. The two new checks fit
  inside the existing CLARITY VERDICT and VERDICT shapes via
  the `Any *-CHECK: FAIL` wildcard rule.
- **The SELF-CONTAINED check creates incentive to write
  better tickets.** It is the operational answer to the
  prevailing convention "tickets should be self-contained" —
  before this ticket the convention is in prose; after this
  ticket it is enforceable at clarity time. A ticket that
  delegates its acceptance to spec sections will fail or warn
  at clarity, prompting the user to inline the relevant spec
  content into the ticket body (Context, Definition of Done,
  or Implementation notes).
- **The SPEC-CONFORMANCE check closes the trust loop.** Today
  the reviewer trusts acceptance items as the spec shadow;
  the new check verifies the shadow is faithful. Without it,
  a well-written acceptance item can pass review while the
  diff materially diverges from what the cited spec says.
  With it, the reviewer's fresh context loads the spec sections
  and the diff and judges them side by side. This is the
  natural extension of M1-010's "reviewer Reads
  engineering-rules in fresh context" pattern.
- **No spec-conformance check for the Plan subagent.** Plan
  fires only on `complexity: high` tickets at `start`. No M1
  ticket carries `complexity: high` today (per M1-010's
  out-of-scope expansion). A follow-up can extend the pattern
  to Plan once a `complexity: high` ticket lands; bundling it
  here would inflate the scope.
- **No spec-conformance check for the threat-actor subagent.**
  Threat-actor reads `docs/spec/security.md` and the diff and
  looks for security gaps. That is already a form of
  spec-conformance check, scoped to security. A separate
  semantic check would conflate concerns. Leave threat-actor's
  prompt template untouched.

## Out-of-scope expansion

- **Engineering rules.** Untouched. The new checks are review
  dimensions, not rules. They sit in the prompt templates and
  agent persona files alongside the existing checks, not in
  `engineering-rules-verbatim.md`.
- **Ticket template.** Untouched. `spec_refs:` already exists;
  no new field is needed for either check.
- **Workflow doc.** Untouched. The doc describes WHAT the
  reviewers receive and the verdicts they return; the new
  checks slot into the existing per-check enumeration without
  a workflow-shape change.
- **Plan subagent prompt template.** Untouched. The plan
  subagent fires only on `complexity: high` tickets, none of
  which are in flight. A follow-up may extend the SELF-CONTAINED
  pattern to plan once a `complexity: high` ticket lands.
- **Threat-actor prompt template.** Untouched. Threat-actor's
  security-gap check already plays a spec-conformance role
  scoped to security; conflating would be churn.
- **Status-regenerator prompt template.** Untouched. Orthogonal
  agent; no review semantics involved.
- **`/m1-tick` skill or its subcommand files.** Untouched. The
  substitution wiring M1-010 provides is exactly enough; no
  new placeholder is added, no new substitution algorithm is
  invoked. The new checks operate on data the prompts already
  carry.
- **`/redteam` skill.** Untouched. Separate skill, separate
  agent, separate prompt template.
- **CLAUDE.md and docs/plan/m1/README.md.** Untouched. The
  §"M1 workflow" summary references checks generically; the
  per-check enumeration is in the prompt templates, which is
  where this ticket adds the two new lines.
- **STATUS.md content.** Regenerator output only.
- **Other ticket files.** M1-001..M1-012 stay untouched.
- **Repo source code, poms, application.properties,
  migrations, tests.** Process-docs-and-agent-definition diff
  only.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The
  suite is currently green at M1-001..M1-003 level; `mvn verify`
  is a smoke check, not a behavioral assertion of these edits.
  The acceptance criteria are checked by grep against the
  edited files and by inspection of the post-edit verdict-block
  and persona-body paragraphs.)

## Alternatives considered

- **Alt A: only add the SELF-CONTAINED check, defer the
  SPEC-CONFORMANCE check.** Rejected: the two checks are the
  two halves of the user's framing ("implementation shouldn't
  need spec" + "reviewer should verify against spec"). Splitting
  them across two tickets would double lifecycle overhead for
  edits that touch the same four files (the prompt templates
  and agent definitions). The bundling precedent is M1-010
  (Alt A reasoning there) and M1-002 (three skill-procedure
  fixes in one ticket).
- **Alt B: only add the SPEC-CONFORMANCE check at review time,
  skip the clarity-side SELF-CONTAINED check.** Rejected: the
  reviewer's check catches divergence after implementation; the
  clarity check catches the upstream cause (under-detailed
  tickets) before implementation. Both are valuable; doing only
  the downstream catch wastes implementation rounds on tickets
  that should have been refined.
- **Alt C: add a new prompt placeholder
  `{{SPEC_SECTIONS_CONTENT}}` substituted by the skill so the
  subagent doesn't need to Read spec files itself.** Rejected:
  it reintroduces exactly the leak M1-010 closes. The subagent's
  fresh-context Read is the documented mechanism; adding a
  skill-side substitution would put spec content back into the
  prompt payload built by main session.
- **Alt D: make SPEC-CONFORMANCE-CHECK WARN-only (cannot
  force REWORK).** Rejected: the user's framing says the
  reviewer should "verify the claim" — which requires the
  ability to reject the claim. A WARN-only check is
  informational; that's not enough when the diff materially
  diverges from the cited spec. The existing wildcard rule
  ("any *-CHECK: FAIL forces VERDICT to be at least REWORK")
  correctly graduates the new check to a blocker for genuine
  mismatches.
- **Alt E: make SELF-CONTAINED-CHECK FAIL-strict (no WARN-
  leaning bias).** Rejected: the judgment is inherently
  subjective. A strict FAIL would block more starts than is
  warranted on tickets where the cited spec is itself tiny or
  the citation is supplementary. The FILES-BUDGET-PLAUSIBLE
  precedent (lean WARN over FAIL) is the right calibration
  for judgment-call checks.
- **Alt F: extend the same pattern to the Plan and
  threat-actor subagents in the same ticket.** Rejected on
  scope grounds. Plan fires rarely (only `complexity: high`);
  threat-actor has its own decoupling per CLAUDE.md §M1
  workflow. Bundling four prompt-template edits would
  inflate the diff and conflate three different review
  surfaces. Each can be a follow-up.
- **Alt G: add a new ticket frontmatter field
  `spec_refs_inlined: true` instead of a SELF-CONTAINED check,
  so the ticket explicitly declares its self-containment.**
  Rejected: a self-declared boolean is gameable (the author
  flips it without doing the work) and adds frontmatter
  shape. A judgment check by a fresh-context subagent that
  has Read access to both the ticket and the cited spec
  sections is harder to game and requires no schema change.
