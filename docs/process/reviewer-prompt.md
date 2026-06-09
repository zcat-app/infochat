# Reviewer subagent prompt template

This is the user prompt the m1-tick skill substitutes and passes to `Agent(subagent_type: "code-reviewer", ...)` for `/m1-tick review <id>`. The agent's identity, tool allowlist (Read/Grep/Glob/Write), and model pinning (opus) are declared in [`.claude/agents/code-reviewer.md`](../../.claude/agents/code-reviewer.md) — those are harness-level enforcement. This template carries only the *task metadata*, the *prompt-supplied paths* the agent reads, the diff stats, and the negative-space list; the ticket body, diff, test log, and the canonical engineering rules are loaded by the agent via Read in its fresh context. The full structured verdict is written to disk before the short chat reply.

---

## Template

```
You are reviewing a single ticket. You have no context from any prior
conversation. Load the rules, read the ticket and diff, then write the
verdict to disk and return a short chat reply.

The ticket is: {{TICKET_ID}}
Round: {{CURRENT_ROUND}}          # 1, 2, or 3 (3 only when round_cap: 3)
Branch: {{BRANCH}}

---

## Inputs to load (Read these before evaluating)

1. Read the ticket file at {{TICKET_FILE_PATH}} with the Read tool.
   Before evaluating anything else, verify the ticket frontmatter
   carries `id: {{TICKET_ID}}`. If the frontmatter id does not match,
   abort the per-check evaluation, Write VERDICT: MANUAL to
   {{VERDICT_FILE_PATH}} with an UNCERTAINTY line citing the mismatch
   ("frontmatter id was X, prompt id was {{TICKET_ID}}"), and return
   the short chat reply with MANUAL + Rework items: 0. Do NOT proceed
   with the per-check evaluation.
2. Read the diff file at {{DIFF_FILE_PATH}} with the Read tool. The
   diff is `git diff main` — working tree vs main, on branch
   {{BRANCH}}. This is the full diff under review.
3. Test log (mvn verify from repo root): {{TEST_LOG_PATH}}
   Use the Read tool. The build summary, any failures, and surrounding
   context are at the bottom of the file. Use Grep to scope if the
   file is large; the bottom carries `BUILD SUCCESS` / `BUILD FAILURE`
   and the test summary.
4. Engineering rules of record: `docs/process/engineering-rules-verbatim.md`
   Use the Read tool. That file is the rule-text-of-record and you
   MUST apply every rule it carries (§1–§8, the stack-specific
   subsection, and the round-N must-shrink subsection), not just the
   ones you find convenient. Do NOT infer "the spirit" of any rule —
   apply the text as written.
5. Verdict file (Write the full structured verdict here using the
   Write tool BEFORE returning your short chat reply):
   {{VERDICT_FILE_PATH}}

Paths above are repo-relative unless prefixed with `/`. The Read and
Write tools accept either form when the agent's CWD is the repo root.

---

## Diff stats

Current round ({{CURRENT_ROUND}}) stats:
  files touched: {{CURRENT_FILES}}
  lines added:   {{CURRENT_ADDED}}
  lines removed: {{CURRENT_REMOVED}}

Previous round ({{PREVIOUS_ROUND}}) stats (populated on rounds ≥ 2;
the substitution is the literal string "(N/A — round 1, no previous
round)" on round 1, in which case the must-shrink check does not apply):
  files touched: {{PREVIOUS_FILES}}
  lines added:   {{PREVIOUS_ADDED}}
  lines removed: {{PREVIOUS_REMOVED}}

---

## Negative-space report (files in `files_scope` that were NOT touched)

The ticket's `files_scope` (when present) lists the path/glob set the
ticket is bounded to. The diff touched some subset of those paths; the
remaining paths are listed below. Judge whether each untouched file is
plausibly a deliberate skip OR looks like a forgotten part of the scope.

{{NEGATIVE_SPACE_LIST}}

(If the ticket has no `files_scope` field, or it is empty, the
substitution above is the literal string "(no path-level scope declared
— files_budget is purely numeric, no negative-space evaluation
applicable)" and you MUST report PASS on NEGATIVE-SPACE-CHECK.)

---

## Ticket-frontmatter rules to apply

These three sections interpret the ticket's frontmatter and stay
inline because they are ticket-data wiring, not rules-of-record. The
rules-of-record are in `engineering-rules-verbatim.md` (input #4
above); read them too before evaluating.

### Files budget and scope (the ticket's frontmatter)

The ticket sets `files_budget: N` (numeric upper bound). The diff MUST
touch at most N files (including new test files). If exceeded, automatic
FAIL on SCOPE-DRIFT-CHECK regardless of how reasonable the extra files
seem. The numeric budget is canonical and always enforced.

The ticket MAY also set `files_scope: [paths/globs]`. When non-empty,
every file in the diff MUST also match an entry in `files_scope`. A
diffed file outside `files_scope` is automatic FAIL on SCOPE-DRIFT-CHECK.
When `files_scope` is empty or absent, only the numeric budget applies
and any path is acceptable (subject to `out_of_scope` exclusions).

**Lifecycle-path exemption.** Three paths are workflow byproducts produced
by the `/m1-tick` and `/redteam` skills themselves, NOT developer choices,
and therefore are EXEMPT from both the numeric-budget check and the
`files_scope` membership check:

  - `docs/plan/m1/STATUS.md` — regenerated by `/m1-tick start`,
    `/m1-tick commit`, and `/m1-tick status` to reflect the ticket's
    own lifecycle transitions (pending → in-progress → in-review → done,
    or → escalated).
  - The operand ticket file at `docs/plan/m1/tickets/{{TICKET_ID}}-*.md`
    — frontmatter is mutated by `/m1-tick start` (sets `status`,
    populates `clarity_check`), `/m1-tick review` (appends to
    `reviews:`), `/m1-tick escalate` (appends to `escalations:`,
    `overrides:`, `revisions:`), and `/m1-tick commit` (sets
    `status: done`).
  - The per-audit verdict file at
    `docs/plan/m1/redteam/{{TICKET_ID}}-*.md` — written by `/redteam
    {{TICKET_ID}}` per `.claude/skills/redteam/SKILL.md` §7, regardless
    of CLEAN-vs-FINDINGS verdict, so the audit is durable across
    `mvn clean` and survives alongside the ticket.

When applying the budget and scope checks: subtract all three paths from
the diff's file count before comparing to `files_budget`, and treat
them as implicitly in `files_scope` for the membership check.
Equivalently: a diff of `<implementation-files> + STATUS.md + ticket-file
+ redteam-verdict-file` is evaluated as if only `<implementation-files>`
were present.

The exemption is one-directional: the three paths are forgiven, never
required. They still remain subject to `out_of_scope` (a ticket may
not list them there in a way that contradicts the workflow) and to
the ordinary "no unrelated edits" judgment (e.g. an unrelated rewrite
of STATUS.md beyond what the regenerator emits would be SCOPE-DRIFT
regardless of the exemption — the exemption covers the *lifecycle-driven*
changes, not arbitrary edits to those paths).

Tickets written before this rule landed (e.g. M1-012) may explicitly
list the lifecycle paths in `files_scope` and a correspondingly inflated
`files_budget`; that is still acceptable — the exemption is additive,
not subtractive. Newly authored tickets should NOT include the
lifecycle paths in `files_scope` or count them in `files_budget` —
declare only the implementation paths.

### Out-of-scope (the ticket's frontmatter `out_of_scope` list)

Any path matching an entry in `out_of_scope` MUST NOT appear in the diff.
Glob patterns are literal globs; treat any match as a failure.

### Acceptance (the ticket's frontmatter `acceptance` list)

Every acceptance item should be checkable. Many are runnable assertions
("mvn -pl X test -Dtest=Y returns success"); the test log either
confirms or denies each. For non-runnable items (e.g. "Flyway
migration applies cleanly"), use the diff and test log to judge.

### Spec-conformance (Read each spec_refs entry in your fresh context)

The ticket's `spec_refs:` frontmatter list names the spec sections the
diff is supposed to implement. SPEC-CONFORMANCE-CHECK verifies that
the diff faithfully implements those sections — not just that the
acceptance items pass as literal strings, but that the diff matches
the cited spec's semantics. Acceptance items can shadow the spec
imperfectly; this check closes the loop.

Read each spec_refs entry in your fresh context (the agent's allowlist
permits Read). These Reads happen in YOUR fresh context, NOT in the
main-session transcript, so spec bytes do not leak back to main.

For each `spec_refs:` entry of the form `<file-path> §<section-title>`:
read by ANCHOR RANGE — not the whole file. Locate the cited heading
using the anchor-resolution algorithm documented in
`docs/process/clarity-prompt.md` (case-insensitive substring match
against `#`-prefixed headings; ambiguity rules; line-number output),
then Read from that line until the next heading at the same-or-higher
depth. Cross-reference clarity-prompt.md rather than duplicating the
algorithm here — that file is the single source of truth for anchor
resolution.

If the citation has no `§<section-title>` (entry is just `<file-path>`),
Read the whole file. If anchor resolution returns ANCHOR-NOT-FOUND or
AMBIGUOUS, fall back to whole-file Read AND raise SPEC-CONFORMANCE-CHECK
to WARN with a note citing the unresolved anchor — the spec-conformance
judgment is still made on the available content, but the operator is
informed that the citation could not be tightened.

Then compare diff semantics to spec semantics:
  - FAIL on a clear mismatch: method/function names in the diff that
    diverge from the spec's named SPI; a behavioral guarantee the spec
    promises that the diff omits (e.g. spec says `the loader is
    idempotent` and the diff has no idempotency guard); the diff
    materially diverges from what the cited section says.
  - WARN on partial coverage: a spec section names N requirements, the
    diff implements M < N of them, and the ticket's acceptance only
    claimed M. Surfaces to the user as informational; does not block
    APPROVE. Also WARN when a spec_refs entry is materially unrelated
    to the diff (the ticket may be over-citing).
  - PASS when the diff faithfully implements the cited sections.

A diff that does the spec thing AND an adjacent thing the spec doesn't
mention is SCOPE-DRIFT-CHECK territory (the existing check covers it),
not SPEC-CONFORMANCE.

### Parameter contracts (engineering-rules-verbatim.md §7a)

§7a nullability contracts are enforced by the build, not by the reviewer
(decision D48). NullAway, built on Error Prone, runs as a compile-time
annotation processor with `NullAway:ERROR` active across every module
under a non-null-by-default model: each `app.zcat.infochat` package is
null-marked, so only genuinely-nullable parameters/returns/fields carry
`@Nullable` (JSpecify) and `@NonNull` is no longer written by hand. A
missing or incorrect contract fails `mvn verify`, which the reviewer
already requires green. There is therefore NO reviewer hand-check of
annotation presence — do not emit a PARAMETER-CONTRACT-CHECK verdict
line, and do not flag a method for lacking `@NonNull`. If the build is
green, the §7a contract holds.

---

## Short chat reply (the only thing you return inline)

After Writing the full structured verdict to {{VERDICT_FILE_PATH}},
return exactly these lines as your chat reply — nothing else, no
preamble, no postscript:

VERDICT: <APPROVE | REWORK | MANUAL>
Verdict file: {{VERDICT_FILE_PATH}}
Rework items: <integer count, 0 on APPROVE>

That is the entire chat reply. The skill parses these three lines
literally; the full per-check structured verdict (with each per-check
PASS/FAIL/WARN reason, each REWORK item, any UNCERTAINTY block) lives
only in the verdict file you wrote, which the skill Reads to populate
the `reviews:` frontmatter entry for this round.

---

## On-disk verdict format (Write this to {{VERDICT_FILE_PATH}} before the chat reply)

Use the Write tool to write the following structured verdict — the
canonical full form, which the skill parses for audit and frontmatter
strings — to {{VERDICT_FILE_PATH}}:

VERDICT: <APPROVE | REWORK | MANUAL>

SCOPE-DRIFT-CHECK: <PASS | FAIL>
  <one paragraph: which changed lines do not trace to acceptance criteria
   or files_budget, or PASS if all do. If FAIL, name specific files/lines.
   On round N ≥ 2, also FAIL if the diff grew along ALL THREE of
   files-touched, lines added, AND lines removed vs round-(N−1) and the
   prior round's REWORK did not authorize a refactor (must-shrink:
   growth along all three is the only failure condition; holding
   equal or shrinking along any dimension is convergent).>

TEST-INTEGRITY-CHECK: <PASS | FAIL>
  <one paragraph: any forbidden patterns introduced (see
   engineering-rules-verbatim.md §8 — syntactic, semantic, authorization,
   stack-specific), or PASS if none. If FAIL, name the specific pattern
   and the file:line.>

OUT-OF-SCOPE-CHECK: <PASS | FAIL>
  <one paragraph: any file or path in `out_of_scope` was touched, or PASS.>

NEGATIVE-SPACE-CHECK: <PASS | WARN>
  <one paragraph: list of files in files_scope that were NOT touched.
   PASS if every untouched file is plausibly a deliberate skip; WARN
   if any look like forgotten parts of the scope. Heuristic: a file is
   "deliberate skip" if its name suggests a sibling concern the ticket
   acceptance criteria don't mention (e.g. acceptance covers RSS fetch,
   files_scope includes a Bluesky sibling, the Bluesky file is untouched);
   it's "forgotten" if its name aligns with an acceptance item that the
   diff appears to address by other means or not at all. WARN does not
   force REWORK; it surfaces to the user as informational. If the
   prompt's negative-space block is the no-scope-declared sentinel
   (no files_scope on the ticket), report PASS.>

ACCEPTANCE-CHECK: <PASS | PARTIAL | FAIL>
  <one bullet per acceptance item, with PASS / FAIL / SKIPPED and a
   one-line reason citing the test log or diff.>

SPEC-CONFORMANCE-CHECK: <PASS | WARN | FAIL>
  <one paragraph: did the diff faithfully implement the spec sections
   cited in spec_refs (PASS), partially implement them (WARN), or
   materially diverge (FAIL)? Cite the spec section and the diff hunk
   that conflicts. Per the Spec-conformance section above, Read each
   spec_refs entry by anchor range in your fresh context; on
   ANCHOR-NOT-FOUND or AMBIGUOUS, raise to WARN with a note.>

REWORK ITEMS: (omit on APPROVE; required on REWORK)
  1. <specific, addressable, scoped to the existing diff>
  2. <as many as needed; each must be actionable without re-architecting>

UNCERTAINTY: (required on MANUAL; omit otherwise)
  <what is unclear, what the resolution options are, why this can't be
   auto-resolved by another rework round>

---

## Verdict rules

- Any *-CHECK: FAIL forces VERDICT to be at least REWORK.
  APPROVE requires every check to be PASS (NEGATIVE-SPACE-CHECK: WARN
  is permitted under APPROVE — it surfaces to the user as informational
  and does not block the commit).
- ACCEPTANCE-CHECK: PARTIAL is REWORK unless the **ticket body itself**
  explicitly names a deferred dependency for the missing item (e.g. a
  bullet under "Definition of Done" that reads "deferred to M<N>-XXX"
  or an `acceptance:` item flagged "blocked on M<N>-XXX"), in which
  case use MANUAL. You are NOT required to crawl the ticket graph or
  read other ticket files; if the citation isn't visible in the ticket
  in front of you, treat the missing item as REWORK.
- TEST-INTEGRITY-CHECK: FAIL with developer rationale "this is fine
  because ..." is MANUAL, not REWORK. Test integrity is not
  developer-overridable (see engineering-rules-verbatim.md §8).
- MANUAL is for genuine reviewer uncertainty: ambiguous spec,
  conflicting rules between the ticket and the canonical rules, or no
  clear path to resolution. Use sparingly; loop indicators are REWORK,
  not MANUAL.
- REWORK ITEMS must be specific. "Refactor for clarity" is too vague;
  "rename Foo.bar() → Foo.baz() to match docs/spec/X.md §Y" is
  acceptable.

Write the full verdict to {{VERDICT_FILE_PATH}} first, then return
ONLY the three-line short chat reply specified above. The skill parses
both literally.
```

---

## Skill responsibilities (what `/m1-tick review` does around the prompt)

1. Resolves the ticket file path, the branch, and the slug.
2. Captures the working-tree-vs-main diff on the branch: `git add -N` on any untracked-but-present files first (intent-to-add, so file paths show up in the diff), then `git diff main` for the full diff. Writes the diff to `target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.diff` and substitutes that path as `{{DIFF_FILE_PATH}}`. The `-N` entries are absorbed by the explicit `git add` at commit time and need no separate cleanup. A commit-range diff against `main` would be empty here because `commit` runs after `review`.
3. Computes diff stats: files touched count, net lines added, net lines removed (`git diff main --shortstat`). Stores under `reviews[].diff_stats` in frontmatter for cross-round comparison; substitutes the numbers into the `{{CURRENT_FILES}}` / `{{CURRENT_ADDED}}` / `{{CURRENT_REMOVED}}` placeholders (and the previous-round counterparts on rounds ≥ 2).
4. Builds the **negative-space list**: if the ticket has a non-empty `files_scope`, computes the set of files matching any glob in `files_scope` that are NOT in the diff and substitutes them as `{{NEGATIVE_SPACE_LIST}}`. If `files_scope` is empty or absent, the substitution is the literal sentinel string `(no path-level scope declared — files_budget is purely numeric, no negative-space evaluation applicable)` and the reviewer must report PASS on `NEGATIVE-SPACE-CHECK`.
5. Locates the most recent `mvn verify` log at `target/m1-tick-test-{{ID}}-r{{CURRENT_ROUND}}.log` and substitutes its path as `{{TEST_LOG_PATH}}`.
6. Pre-allocates a verdict file path under `target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.txt` and substitutes it as `{{VERDICT_FILE_PATH}}`.
7. Substitutes the remaining placeholders: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (repo-relative), `{{BRANCH}}`.
8. Spawns `Agent(subagent_type: "code-reviewer", prompt: <substituted>)`. Foreground.
9. Parses the three-line short chat reply for the verdict line and integer rework-item count.
10. Reads `{{VERDICT_FILE_PATH}}` from disk to extract per-check results, REWORK ITEMS strings, and any UNCERTAINTY block for the ticket frontmatter `reviews:` entry.
11. Updates the ticket frontmatter:
    - On `APPROVE`: status stays `in-review`; the verdict is recorded under a `reviews:` list with timestamp + round + diff stats; user is prompted to run `/m1-tick commit`.
    - On `REWORK` round 1: status returns to `in-progress`; rework items are appended to the ticket body under a "Round 1 rework" section; the developer fixes only those items.
    - On `REWORK` round 2: if the ticket's `round_cap` is `3`, status returns to `in-progress` and a "Round 2 rework" section is appended. Otherwise status moves to `escalated`; the five-way menu fires.
    - On `REWORK` round 3 (only reachable when `round_cap: 3`): status moves to `escalated` regardless.
    - On `MANUAL`: status moves to `escalated` immediately; the five-way menu fires.

The reviewer never edits source files. It Reads its inputs, Writes the full verdict to {{VERDICT_FILE_PATH}}, returns the short chat reply, and exits.
