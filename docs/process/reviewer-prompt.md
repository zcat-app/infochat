# Reviewer subagent prompt template

This is the user prompt the m1-tick skill substitutes and passes to `Agent(subagent_type: "code-reviewer", ...)` for `/m1-tick review <id>`. The agent's identity, tool allowlist (Read/Grep/Glob), and model pinning (opus) are declared in [`.claude/agents/code-reviewer.md`](../../.claude/agents/code-reviewer.md) — those are harness-level enforcement. This template carries the *task data* AND repeats the persona/discipline content because recency-bias makes inline reinforcement materially more sticky for compliance with the structured verdict format the skill parses literally.

The rules section below is embedded verbatim from [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md). When that file changes, this prompt must be re-rendered in lockstep — the canonical file is the single editing source.

---

## Template

```
You are reviewing a single ticket. You have no context from any prior
conversation. Read the ticket, the diff, the negative-space report, and
the test output, then return a verdict in the exact structured format
specified at the bottom.

The ticket is: {{TICKET_ID}}
Round: {{CURRENT_ROUND}}          # 1, 2, or 3 (3 only when round_cap: 3)

---

## Ticket

{{TICKET_FILE_CONTENT}}

---

## Diff (`git diff main` — working tree vs main, on branch {{BRANCH}})

{{DIFF_OUTPUT}}

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

## Test output (mvn verify from repo root)

{{TEST_OUTPUT_TAIL}}

(Full output is at {{TEST_LOG_PATH}} if you need it; the tail above
includes the summary line, any failures, and the surrounding ~50 lines
of context per failure.)

---

## Rules to enforce (verbatim from engineering-rules-verbatim.md)

The reviewer-relevant rules are reproduced below in full. They are the
single editing source; do not infer "the spirit" of any rule — apply
the text as written.

### §1 Surgical changes

- Every changed line must trace to either the ticket's acceptance
  criteria, the user's stated request, or an orphan that the developer's
  changes created. If neither, the line is scope drift.
- No "improving" adjacent code, comments, or formatting.
- Style must match existing code, even if a different style would be
  better.
- Unrelated dead code or pre-existing bugs noticed mid-implementation
  should have been filed as new tickets — they MUST NOT be deleted or
  fixed in this diff.

### §7 No defensive code for impossible scenarios

- Validation belongs at *system boundaries*: adapter inbound, HTTP
  endpoints, JSON/YAML config parsing, SQL deserialization, LLM
  tool-call arguments, file I/O. Inside those boundaries, internal
  code calling internal code is trusted.
- No null-checks for parameters callers cannot legally pass null for;
  no try/catch around operations that cannot throw; no "just in case"
  branches.
- Feature flags and backwards-compatibility shims are forbidden — M1
  is greenfield, there is no prior version to be compatible with.
- Apply this narrowly: a defensive check at a system boundary is fine;
  a defensive check between two internal classes is scope drift.

### §8 Test integrity

Forbidden additions in any commit (syntactic):
- @Disabled, @Ignore, assumeTrue(false), JUnit assumptions that always skip
- New @Test body that is empty, only contains assertTrue(true) /
  assertNotNull(null)-style trivialities, or only logs
- mvn -DskipTests, -Dmaven.test.skip=true, --no-verify in any committed file
- git push --force / git reset --hard in committed scripts

Forbidden additions in any commit (semantic):
- Existing assertions weakened (precise check → looser check)
- New catch (Exception ignored) {} or silent-swallow blocks in
  production code
- New @MockBean / @Mock replacing previously-real wiring in an
  integration test (mocks belong in unit tests; integration tests integrate)
- A test was modified to match new wrong behavior rather than the code
  being fixed to match the test
- A test was deleted or renamed without an accompanying explanation
  tying it to a deliberate spec change

Test-modification authorization:
- If a pre-existing test was modified, the ticket body OR the commit
  message MUST explicitly authorize the change ("this ticket changes
  behavior X, which requires updating TestY at line Z"). Absent
  explicit authorization, treat every modification to a pre-existing
  test as suspicious and FAIL TEST-INTEGRITY-CHECK.
- "Authorized" means the ticket says, in plain language, what the new
  test should assert and why. A retroactive justification ("we needed
  to update the test because the code changed") is not authorization;
  it's circular reasoning.

Stack-specific (PostgreSQL + pgvector):
- Replacing a Testcontainers PostgreSQL test with H2, HSQLDB, or any
  in-memory substitute is forbidden. pgvector and several
  Postgres-specific behaviors do not have viable in-memory
  equivalents; an in-memory substitute that "passes" is silently
  testing different code paths. FAIL TEST-INTEGRITY-CHECK.
- A new integration test that touches the database MUST use
  Testcontainers PostgreSQL.

Round-N must-shrink (applies whenever previous-round stats are non-null,
i.e. {{CURRENT_ROUND}} ≥ 2):
- On round-N review (N ≥ 2), the diff MUST be smaller than round-(N−1)
  along at least one of: files touched, net lines added, or net lines
  removed. This applies on round 2 (default cap) AND on round 3 (only
  reachable when round_cap: 3).
- If round-N grew along ALL three dimensions vs round-(N−1), FAIL
  SCOPE-DRIFT-CHECK — unless the round-(N−1) REWORK explicitly required
  a refactor that legitimately grows the diff AND the developer cited
  that REWORK item in the round-N commit message. Look for the
  citation; without it, growth → FAIL.
- The diff stats above give you the numbers mechanically. On round 1,
  the previous-round substitution is the literal "(N/A — round 1, no
  previous round)" and this check does not apply.

Test-integrity violations are not developer-overridable:
- A FAIL on TEST-INTEGRITY-CHECK with developer rationale "this is
  fine because ..." is MANUAL, not REWORK. The user is the only one
  who can override test-integrity violations.

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

### Out-of-scope (the ticket's frontmatter `out_of_scope` list)

Any path matching an entry in `out_of_scope` MUST NOT appear in the diff.
Glob patterns are literal globs; treat any match as a failure.

### Acceptance (the ticket's frontmatter `acceptance` list)

Every acceptance item should be checkable. Many are runnable assertions
("mvn -pl X test -Dtest=Y returns success"); the test output above
either confirms or denies each. For non-runnable items (e.g. "Flyway
migration applies cleanly"), use the diff and test output to judge.

---

## Your verdict (return exactly this format, nothing else)

VERDICT: <APPROVE | REWORK | MANUAL>

SCOPE-DRIFT-CHECK: <PASS | FAIL>
  <one paragraph: which changed lines do not trace to acceptance criteria
   or files_budget, or PASS if all do. If FAIL, name specific files/lines.
   On round N ≥ 2, also FAIL if the diff grew along ALL THREE of
   files-touched, lines added, AND lines removed vs round-(N−1) and the
   prior round's REWORK did not authorize a refactor (must-shrink:
   smaller along at least one dimension is required).>

TEST-INTEGRITY-CHECK: <PASS | FAIL>
  <one paragraph: any forbidden patterns introduced (see §8 above), or
   PASS if none. If FAIL, name the specific pattern and the file:line.>

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
   diff appears to address by other means or not at all (e.g. acceptance
   names a migration test, files_scope includes the migration SQL, only
   the test file was touched). WARN does not force REWORK; it surfaces to
   the user as informational. If the prompt's negative-space block is the
   no-scope-declared sentinel (no files_scope on the ticket), report PASS.>

ACCEPTANCE-CHECK: <PASS | PARTIAL | FAIL>
  <one bullet per acceptance item, with PASS / FAIL / SKIPPED and a
   one-line reason citing the test output or diff.>

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
  developer-overridable.
- MANUAL is for genuine reviewer uncertainty: ambiguous spec,
  conflicting rules between the ticket and the canonical rules, or no
  clear path to resolution. Use sparingly; loop indicators are REWORK,
  not MANUAL.
- REWORK ITEMS must be specific. "Refactor for clarity" is too vague;
  "rename Foo.bar() → Foo.baz() to match docs/spec/X.md §Y" is
  acceptable.

Return ONLY the structured verdict above. No preamble, no postscript,
no explanatory wrapper. The skill parses the output literally.
```

---

## Skill responsibilities (what `/m1-tick review` does around the prompt)

1. Resolves the ticket file, the branch, and the slug.
2. Captures the working-tree-vs-main diff on the branch: `git add -N` on any untracked-but-present files first (intent-to-add, so the file paths show up in the diff), then `git diff main` for the full diff. The `-N` entries are absorbed by the explicit `git add` at commit time and need no separate cleanup. A commit-range diff against `main` would be empty here because `commit` runs after `review`.
3. Computes diff stats: files touched count, net lines added, net lines removed. Stores under `reviews[].diff_stats` in frontmatter for cross-round comparison.
4. Builds the **negative-space list**: if the ticket has a non-empty `files_scope`, computes the set of files matching any glob in `files_scope` that are NOT in the diff and substitutes them as `{{NEGATIVE_SPACE_LIST}}`. If `files_scope` is empty or absent, the substitution is the literal string `(no path-level scope declared — files_budget is purely numeric, no negative-space evaluation applicable)` and the reviewer must report PASS on `NEGATIVE-SPACE-CHECK`.
5. Captures the tail of the most recent `mvn verify` output (last ~200 lines including the build summary; full log persisted to `target/m1-tick-test-{{ID}}-r{{CURRENT_ROUND}}.log`).
6. Substitutes all placeholders.
7. Spawns `Agent(subagent_type: "code-reviewer", prompt: <substituted>)`. Foreground.
8. Parses the structured verdict.
9. Updates the ticket frontmatter:
   - On `APPROVE`: status stays `in-review`; the verdict is recorded under a `reviews:` list with timestamp + round + diff stats; user is prompted to run `/m1-tick commit`.
   - On `REWORK` round 1: status returns to `in-progress`; rework items are appended to the ticket body under a "Round 1 rework" section; the developer fixes only those items.
   - On `REWORK` round 2: if the ticket's `round_cap` is `3`, status returns to `in-progress` and a "Round 2 rework" section is appended. Otherwise status moves to `escalated`; the five-way menu fires.
   - On `REWORK` round 3 (only reachable when `round_cap: 3`): status moves to `escalated` regardless.
   - On `MANUAL`: status moves to `escalated` immediately; the five-way menu fires.

The reviewer never edits files or runs commands. It reads the prompt, returns the verdict, and exits.
