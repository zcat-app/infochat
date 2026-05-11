---
id: M1-010
title: Slim m1-tick subagent prompts
status: done
created: 2026-05-11
last_updated: 2026-05-11
blocked_by: []
files_budget: 5
files_scope:
  - docs/process/clarity-prompt.md
  - docs/process/reviewer-prompt.md
  - .claude/agents/clarity-reviewer.md
  - .claude/agents/code-reviewer.md
  - .claude/skills/m1-tick/SKILL.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any edit to docs/process/redteam-prompt.md or .claude/agents/threat-actor.md or .claude/skills/redteam/ (the threat-actor skill is intentionally decoupled per CLAUDE.md §M1 workflow; same substitution pattern there is its own follow-up if it becomes a token-cost concern)
  - any edit to docs/process/plan-prompt.md (the Plan subagent only fires on `complexity: high` start; the dominant per-invocation cost the user flagged is the always-running clarity pre-flight and the always-running review, so plan-prompt stays content-inlined for now — a separate follow-up may slim it once a `complexity: high` ticket lands)
  - any edit to docs/process/workflow.md (workflow.md describes WHAT the reviewer receives, not the wire-format of HOW; the change here is procedural and stays inside the prompt-template + skill files)
  - any edit to docs/process/engineering-rules-verbatim.md (the canonical rules file itself stays unchanged; this ticket REMOVES the duplicate inline copy from reviewer-prompt.md and replaces it with a Read pointer to the canonical file, which is the file's "single editing source" intent finally realised — the lockstep-rerendering hazard goes away with the duplicate)
  - any edit to docs/process/ticket-template.md (template defines the ticket shape; ticket-content access is changing, not ticket shape)
  - any change to the verdict semantics — PASS/WARN/FAIL for clarity, APPROVE/REWORK/MANUAL/OVERRIDE-APPROVE for review — only the return-channel split (full to disk, short to chat) changes
  - any change to the round-cap rules, must-shrink rules, or escalation triggers
  - any new subcommand split inside .claude/skills/m1-tick/ (the SKILL.md split is the separate ticket M1-011)
  - any edit to docs/plan/m1/STATUS.md beyond what the regenerator emits
  - any edit to other ticket files under docs/plan/m1/tickets/ (M1-001..M1-009 stay untouched)
  - any change to repo source code, poms, application.properties, migrations, or test code (this ticket only edits process docs, agent definitions, and the skill)
  - any new Maven module or pom change of any kind
acceptance:
  - "grep -nF '{{TICKET_FILE_PATH}}' docs/process/clarity-prompt.md returns at least one match AND grep -nF '{{TICKET_FILE_CONTENT}}' docs/process/clarity-prompt.md returns zero matches"
  - "grep -nF '{{TICKET_FILE_PATH}}' docs/process/reviewer-prompt.md returns at least one match AND grep -nF '{{TICKET_FILE_CONTENT}}' docs/process/reviewer-prompt.md returns zero matches"
  - "grep -nF '{{DIFF_FILE_PATH}}' docs/process/reviewer-prompt.md returns at least one match AND grep -nF '{{DIFF_OUTPUT}}' docs/process/reviewer-prompt.md returns zero matches"
  - "grep -nF '{{TEST_LOG_PATH}}' docs/process/reviewer-prompt.md returns at least one match AND grep -nF '{{TEST_OUTPUT_TAIL}}' docs/process/reviewer-prompt.md returns zero matches"
  - "grep -nF '{{VERDICT_FILE_PATH}}' docs/process/clarity-prompt.md returns at least one match AND grep -nF '{{VERDICT_FILE_PATH}}' docs/process/reviewer-prompt.md returns at least one match (the on-disk verdict file path the skill pre-allocates and substitutes)"
  - "within docs/process/clarity-prompt.md, the template instructs the agent to Read the ticket file (grep -nE 'Read.*the ticket|Use the Read tool' returns at least one match within lines bounded by the `## Template` and `## Skill responsibilities` headings)"
  - "within docs/process/reviewer-prompt.md, the template instructs the agent to Read the ticket file AND the diff file (grep -nE 'Read.*ticket' AND grep -nE 'Read.*diff' each return at least one match within the `## Template` block bounded by `## Skill responsibilities`)"
  - "both prompt templates document a short chat-reply shape carrying ONLY the verdict header line, the verdict-file pointer, and integer counts of blockers/warnings (clarity) or rework-items (review). Verify by grep: 'Short chat reply' or 'short return payload' appears in each of docs/process/clarity-prompt.md and docs/process/reviewer-prompt.md at least once; AND the canonical full structured verdict format is still documented (the existing CLARITY VERDICT / VERDICT line + per-check format remains in each file, just relocated under a section header indicating it is the on-disk form)"
  - "within .claude/skills/m1-tick/SKILL.md, the `## start <id>` section substitutes `{{TICKET_FILE_PATH}}` (grep matches at least one line citing TICKET_FILE_PATH between the `## start <id>` and `## review <id>` headings) AND no longer cites `{{TICKET_FILE_CONTENT}}` for the clarity subagent (the clarity-substitution paragraph references TICKET_FILE_PATH, not TICKET_FILE_CONTENT). The `## start <id>` section ALSO pre-allocates the verdict file path under `target/m1-tick-clarity-{ID}.txt` and substitutes it as `{{VERDICT_FILE_PATH}}`."
  - "within .claude/skills/m1-tick/SKILL.md, the `## review <id>` section substitutes `{{TICKET_FILE_PATH}}` AND `{{DIFF_FILE_PATH}}` AND `{{VERDICT_FILE_PATH}}` (each grep returns at least one match between the `## review <id>` and `## commit <id>` headings); the section ALSO writes the captured diff to `target/m1-tick-review-{ID}-r{ROUND}.diff` BEFORE spawning the subagent, and reads the on-disk verdict file post-subagent to extract review-item / check-result strings for the `reviews:` frontmatter entry"
  - "grep -nF '{{TICKET_FILE_CONTENT}}' .claude/skills/m1-tick/SKILL.md returns zero matches AND grep -nF '{{DIFF_OUTPUT}}' .claude/skills/m1-tick/SKILL.md returns zero matches AND grep -nF '{{TEST_OUTPUT_TAIL}}' .claude/skills/m1-tick/SKILL.md returns zero matches (the inlined-content substitutions are gone from the skill)"
  - "both .claude/agents/clarity-reviewer.md and .claude/agents/code-reviewer.md describe the new contract: each file mentions Read-the-ticket-from-the-path-the-prompt-supplies AND mentions writing the full verdict to disk before returning the short chat reply. Verify by grep: each agent file matches both `Read` (in the context of loading the ticket) AND `Write` (in the context of writing the verdict) at least once each in its persona/discipline body (lines after the YAML frontmatter)"
  - "structural quality check (non-numeric): the substituted prompt that the skill sends to the clarity subagent and the code-reviewer subagent NO LONGER contains the ticket body verbatim, NO LONGER contains the full diff verbatim, and NO LONGER contains the test output tail verbatim. Verifiable by inspection of the post-edit `## start <id>` and `## review <id>` substitution paragraphs in .claude/skills/m1-tick/SKILL.md: the Agent call's `prompt` argument is built from path placeholders only, not from file-content reads."
  - "spec_refs resolution moves out of the main session into the clarity subagent. Verify: grep -nF '{{SPEC_REF_RESOLUTIONS}}' docs/process/clarity-prompt.md returns zero matches AND grep -nF 'Spec sections cited' docs/process/clarity-prompt.md returns zero matches (the misleading 'verbatim' heading and the resolution-block placeholder are both gone). Within docs/process/clarity-prompt.md the `## Template` block instructs the subagent to resolve spec_refs itself (grep -niE 'resolve.*spec_refs|each spec_ref' returns at least one match between the `## Template` and `## Skill responsibilities` headings). Within .claude/agents/clarity-reviewer.md the persona body (post-frontmatter) mentions that the agent resolves spec_refs itself (grep -niE 'resolve.*spec_refs|each spec_ref' returns at least one match after the YAML frontmatter)."
  - "in .claude/skills/m1-tick/SKILL.md the `## start <id>` clarity-substitution paragraph (between the `## start <id>` and `## review <id>` headings) no longer cites {{SPEC_REF_RESOLUTIONS}}. The spec_refs anchor resolution algorithm itself stays in the file because the plan-subagent substitution (step 7, complexity: high path) still uses it — but it is NO LONGER reached on the common clarity path. Verify: within the `## start <id>` block, the step that spawns the clarity subagent (`subagent_type: \"clarity-reviewer\"`) does not mention {{SPEC_REF_RESOLUTIONS}}."
  - "the embedded engineering-rules block is removed from docs/process/reviewer-prompt.md and replaced with a Read pointer to the canonical file. Verify: grep -nE '^### §1 Surgical changes' docs/process/reviewer-prompt.md returns zero matches AND grep -nE '^### §7 No defensive code' docs/process/reviewer-prompt.md returns zero matches AND grep -nE '^### §8 Test integrity' docs/process/reviewer-prompt.md returns zero matches AND grep -nF 'engineering-rules-verbatim.md' docs/process/reviewer-prompt.md returns at least one match (the Read pointer cites the canonical file). The round-N must-shrink rule remains accessible to the reviewer because engineering-rules-verbatim.md already carries it — no separate inline preservation is needed."
  - "the reviewer-prompt.md sections that interpret ticket frontmatter (`Files budget and scope`, `Out-of-scope`, `Acceptance`) STAY inline — those are ticket-frontmatter interpretation, not rules-of-record. Verify: grep -nE '^### Files budget and scope|^### Out-of-scope|^### Acceptance' docs/process/reviewer-prompt.md returns three matches."
  - "both prompt templates include an ID-verification guard so the subagent confirms the file it Read has matching frontmatter before reviewing. Verify: grep -nF '{{TICKET_ID}}' docs/process/clarity-prompt.md returns at least two matches (the ID is substituted into BOTH the existing 'The ticket is' line AND a new explicit verify-before-evaluating instruction inside the `## Template` block) AND grep -nF '{{TICKET_ID}}' docs/process/reviewer-prompt.md returns at least two matches (same shape — existing 'The ticket is' line plus the new verify instruction)."
  - "mvn -B verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (this ticket only edits process docs, agent definitions, and the skill; mvn verify is a smoke check that no source code was perturbed)
spec_refs:
  - docs/process/clarity-prompt.md §Template
  - docs/process/clarity-prompt.md §Return exactly this format
  - docs/process/clarity-prompt.md §Skill responsibilities
  - docs/process/reviewer-prompt.md §Template
  - docs/process/reviewer-prompt.md §Your verdict
  - docs/process/reviewer-prompt.md §Skill responsibilities
  - docs/process/engineering-rules-verbatim.md §Engineering rules
  - .claude/skills/m1-tick/SKILL.md §start
  - .claude/skills/m1-tick/SKILL.md §review
  - .claude/agents/clarity-reviewer.md §Tool use
  - .claude/agents/code-reviewer.md §How you read the prompt
decision_refs: []

reviews:
  - round: 1
    date: 2026-05-11
    verdict: APPROVE
    agent_run: a38594369182fb161
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 284
      removed: 232
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-11
  verdict: WARN
  warnings:
    - "Acceptance item 13 is a non-runnable structural quality check (explicitly labelled 'non-numeric'); relies on inspection of the `## start <id>` and `## review <id>` substitution paragraphs in SKILL.md rather than a grep. Does not block — items 9, 10, 11 cover the same structural property via grep — but the developer should inspect the substitution paragraphs directly to satisfy item 13."
  blockers: []
---

# M1-010: Slim m1-tick subagent prompts

## Context

A single `/m1-tick start <id>` invocation consumed ~100k tokens
before any implementation began. The skill structurally does the
right thing — it delegates ticket-clarity pre-flight and code
review to fresh-context subagents — but the substitution wiring
inlines content that the subagents could load themselves, and the
subagents return verbatim structured verdicts the main session
only needs the header and counts of.

Five concrete leaks:

1. `docs/process/clarity-prompt.md` substitutes `{{TICKET_FILE_CONTENT}}`,
   so the main session reads the ticket body and inlines it into
   the prompt parameter passed to `Agent`. The ticket content is
   then in the main-session transcript AND in the subagent's
   context window — paid twice. The clarity-reviewer subagent
   already has Read/Grep/Glob; it can load the ticket itself.
2. The clarity subagent returns its full 8-check structured verdict
   to the main session. The skill acts only on the verdict line
   and the blocker/warning lists; the rest is verbose context the
   main session pays bytes for.
3. `docs/process/reviewer-prompt.md` has the same two issues, larger
   in magnitude: `{{TICKET_FILE_CONTENT}}` plus `{{DIFF_OUTPUT}}`
   (potentially many KB of diff) plus `{{TEST_OUTPUT_TAIL}}` (~200
   lines of mvn output) all substituted inline.
4. The `## start <id>` step 1 resolution algorithm reads every spec
   file cited by the ticket's `spec_refs:` into the **main session**
   to build `{{SPEC_REF_RESOLUTIONS}}`. For tickets that cite
   `docs/spec/security.md`, `docs/spec/architecture.md`,
   `docs/spec/commands.md`, etc., this is tens of KB of spec text
   accumulating in main-session transcript on every `start`,
   completely independent of the ticket-body inlining. The clarity
   subagent already has Read/Grep; resolving the refs inside the
   fresh-context subagent moves the spec-file content out of the
   main session entirely.
5. `docs/process/reviewer-prompt.md` embeds `§1`, `§7`, `§8` of
   `engineering-rules-verbatim.md` verbatim — ~85 lines of duplicated
   rule text plus the lockstep-rerendering hazard the canonical
   file's own header calls out. The code-reviewer subagent has Read
   in fresh context; a Read into fresh context preserves the same
   recency that inlining gives — the rules are the most recent
   thing the subagent loaded either way. Inlining buys nothing here
   beyond a maintenance liability.

This ticket lands the substitution-and-return-channel split for
clarity and review, moves spec_refs resolution into the clarity
subagent's fresh context, and replaces the embedded engineering-
rules block with a Read pointer to the canonical file. It also
adds a small ID-verification guard to both prompt templates — once
the subagent loads the ticket from a prompt-supplied path, a
mis-substituted path would otherwise be silently consumed; the
guard makes the failure mode loud.

The Plan subagent (`docs/process/plan-prompt.md`, only fires on
`complexity: high`) and the threat-actor subagent
(`docs/process/redteam-prompt.md`, separate `/redteam` skill) carry
the same patterns but are explicitly out of scope here — clarity
runs on EVERY start and review runs on EVERY review, so the
return-on-effort is concentrated on those two. The spec_refs
resolution algorithm itself STAYS in `SKILL.md` because the plan-
subagent substitution (step 7) still uses it on the rare
`complexity: high` path; only the common clarity path stops
calling it.

The SKILL.md per-subcommand split — a second per-invocation savings
because every `/m1-tick <anything>` currently loads the full 53 KB
SKILL.md — is the separate ticket M1-011.

## Definition of Done

- `docs/process/clarity-prompt.md` substitutes a ticket file PATH,
  not the ticket file content. The template instructs the clarity
  subagent to use its Read tool to load the path. The
  `{{TICKET_FILE_CONTENT}}` placeholder is gone.
- `docs/process/clarity-prompt.md` introduces a `{{VERDICT_FILE_PATH}}`
  placeholder. The template instructs the subagent to write the
  full 8-check structured verdict to that path and return ONLY a
  short header-plus-counts payload to chat (`CLARITY VERDICT: <v>` +
  pointer to the file + integer blocker count + integer warning
  count). The canonical full-verdict format is still documented
  in the template under a section that makes clear "this is the
  on-disk form".
- `docs/process/clarity-prompt.md` no longer carries
  `{{SPEC_REF_RESOLUTIONS}}` or its misleading "Spec sections
  cited (verbatim, ...)" heading. The template instructs the
  clarity subagent to resolve each `spec_refs:` entry itself from
  the ticket frontmatter using Read/Grep — the same anchor-match
  algorithm currently documented in `SKILL.md`, just executed in
  the subagent's fresh context instead of the main session's.
- `docs/process/reviewer-prompt.md` substitutes ticket file PATH,
  diff file PATH, and test log PATH instead of inlining their
  contents. The template instructs the code-reviewer to Read each
  of those paths. The `{{TICKET_FILE_CONTENT}}`, `{{DIFF_OUTPUT}}`,
  and `{{TEST_OUTPUT_TAIL}}` placeholders are gone; `{{DIFF_FILE_PATH}}`
  and `{{TEST_LOG_PATH}}` (already present in the prior template
  as a side-pointer) are the canonical references.
- `docs/process/reviewer-prompt.md` introduces the same
  `{{VERDICT_FILE_PATH}}` mechanism (different on-disk filename
  per the skill — review goes under `target/m1-tick-review-{ID}-r{ROUND}.txt`).
  The subagent writes the full structured verdict to disk and
  returns a short header-plus-counts payload to chat.
- `docs/process/reviewer-prompt.md`'s embedded engineering-rules
  block (`### §1 Surgical changes`, `### §7 No defensive code …`,
  `### §8 Test integrity` and their subsections) is replaced with
  a one-paragraph instruction telling the code-reviewer to Read
  `docs/process/engineering-rules-verbatim.md` before evaluating.
  The canonical file (94 lines, §1–§8 plus stack-specific and
  round-N must-shrink) is loaded once by the subagent in fresh
  context; the lockstep-rerendering hazard the canonical file's
  own header warns about is finally gone. The reviewer-prompt
  sections that interpret ticket frontmatter (`Files budget and
  scope`, `Out-of-scope`, `Acceptance`) STAY inline — they are
  reviewer-procedure, not rules-of-record.
- Both prompt templates include an ID-verification guard: a
  one-line instruction telling the subagent to confirm the file
  it Read has `id: {{TICKET_ID}}` in its frontmatter before
  evaluating anything. On mismatch, the clarity subagent returns
  CLARITY VERDICT: FAIL with a BLOCKERS line naming the mismatch;
  the code-reviewer returns VERDICT: MANUAL with UNCERTAINTY
  naming the mismatch. The cost is ~1 line of prompt; the
  protection is against an entire class of substitution bugs the
  new path-based contract introduces.
- The negative-space list and the diff stats (current and previous
  round) STAY inline in the reviewer prompt — these are small
  bounded metadata, not file content, and the reviewer uses them
  mechanically. Don't move them to disk; that would be churn
  without a token win.
- `.claude/agents/clarity-reviewer.md` and `.claude/agents/code-reviewer.md`
  are updated so their persona descriptions match the new contract:
  load the ticket file via the path the prompt supplies (Read);
  resolve spec_refs themselves (clarity only); Read
  `engineering-rules-verbatim.md` before reviewing (code-reviewer
  only); confirm the ticket ID matches before evaluating; write
  the full structured verdict to disk (Write); return only the
  short payload to chat. The agent files keep the same tool
  allowlist (Read, Grep, Glob) PLUS Write so they can persist the
  verdict file. Model pinning (sonnet / opus) and color stay.
- `.claude/skills/m1-tick/SKILL.md` is updated in the `## start <id>`
  and `## review <id>` sections to: (a) substitute path placeholders
  instead of inlining content; (b) pre-allocate the verdict file
  path under `target/`; (c) write the diff (and test-log path,
  which already exists) for the reviewer to Read; (d) parse the
  short chat-reply for verdict + counts; (e) read the on-disk
  verdict file when it needs blocker/warning/check-result strings
  for the ticket frontmatter; (f) drop the main-session spec_refs
  resolution from the clarity-substitution step — the algorithm
  itself stays in `SKILL.md` because step 7 (plan-subagent,
  `complexity: high` path) still uses it. No other section of
  SKILL.md changes.
- `mvn -B verify` from the repo root exits 0.

## Implementation notes

- **The `target/` directory is already the persistence convention.**
  `target/m1-tick-test-{ID}-r{ROUND}.log` is mentioned in the
  current SKILL.md and reviewer-prompt.md. New files follow the
  same shape:
  - `target/m1-tick-clarity-{ID}.txt` — full clarity verdict.
  - `target/m1-tick-review-{ID}-r{ROUND}.diff` — captured diff.
  - `target/m1-tick-review-{ID}-r{ROUND}.txt` — full review verdict.
  These are workflow artifacts; they need not be committed (the
  `Reviewed-by:` trailer on the eventual commit is the durable
  audit trail).
- **Adding Write to the agent tool allowlists.** Today both
  clarity-reviewer.md and code-reviewer.md cap the tool allowlist
  at Read/Grep/Glob. The new contract requires Write so the agent
  can persist the full verdict. Update the `tools:` line in each
  frontmatter to `Read, Grep, Glob, Write`. Both agents remain
  read-only with respect to repo source — Write is only used to
  put the verdict at the pre-allocated workflow path inside
  `target/`. The agent persona text in each file should call this
  out explicitly (one-liner: "Write is allowed only at the
  `{{VERDICT_FILE_PATH}}` the prompt supplies").
- **What the substituted prompt looks like after the edits.** For
  clarity, the substituted Agent prompt is ~ the size of
  clarity-prompt.md itself (~7.6 KB) PLUS the resolved spec_refs
  block (~ a few hundred bytes) PLUS the path strings (negligible).
  Today it is that base PLUS the inlined ticket body (~10–20 KB).
  Roughly 60–70% reduction on the per-clarity prompt size in
  main's transcript. For review, the inlined diff can dominate
  for tickets like M1-005 with many touched files; moving it to
  disk recovers the diff bytes from main's transcript.
- **What the subagent's reply looks like after the edits.** Today
  it is the full 8-check (clarity) or 5-check + rework-items
  (review) structured block, several KB. After: ~3–5 lines
  ("CLARITY VERDICT: PASS" + pointer + "Blockers: 0" + "Warnings: 1",
  or "VERDICT: APPROVE" + pointer + "Rework items: 0").
- **The skill still needs blocker/warning/rework-item text** for
  the frontmatter (`clarity_check:` and `reviews:`). It reads
  those from `{{VERDICT_FILE_PATH}}` after the subagent returns.
  This Read happens in the main session — pays the bytes once,
  not twice (the prompt no longer carried them; the reply no
  longer carried them; only the post-return parse does).
- **Substitution algorithm for `{{TICKET_FILE_PATH}}`.** The
  ticket file path is `docs/plan/m1/tickets/M1-NNN-<slug>.md` —
  the same path the skill already computes for the branch and
  the commit step. Substitute the absolute (or repo-relative;
  pick one in the skill and document it) path string. The
  subagent's Read tool accepts absolute paths; relative paths
  work too if the agent's CWD is the repo root. The skill should
  pick the unambiguous form — repo-relative — and write a one-
  liner in each prompt template noting "paths are repo-relative
  unless prefixed with `/`".
- **Substitution algorithm for `{{DIFF_FILE_PATH}}`.** Before
  spawning the reviewer subagent, the skill captures `git diff main`
  (already documented in `## review <id>` step 2) to a file under
  `target/`. Today it captures the diff into the prompt; with this
  ticket it captures into a file and substitutes the path. The
  `git add -N` intent-to-add prelude (so newly-created files
  appear in the diff) is unchanged — it is needed regardless of
  where the diff lands.
- **No new substitution algorithm for `{{TEST_LOG_PATH}}`.** The
  reviewer prompt already documents this path (line ~70). The
  edit moves the agent's instruction from "the tail above includes
  the summary" to "Read this log; the build summary, any failures,
  and the surrounding context are at the bottom of the file —
  use Grep to scope if the file is large". No new file is written
  by this ticket; the existing log path is just referenced
  directly instead of having its tail inlined.
- **The persona-discipline content in the prompt template** (per
  the clarity-prompt.md note at the top — "this template carries
  the *ticket data* AND repeats the persona/discipline content
  because recency-bias makes inline reinforcement materially
  more sticky") STAYS in the template body. The token cost of
  the persona block is fixed; the leaks are the variable-size
  inlined contents.
- **Spec_refs resolution in the clarity subagent.** Today the
  main session executes the `spec_refs` anchor-match algorithm
  documented in `SKILL.md` `## start <id>` step 1: for each
  `<file-path> §<section-title>` entry it Reads the cited file,
  scans for `#`-prefixed headings, case-insensitive substring-
  matches, picks the best match, and emits a FOUND/ANCHOR-NOT-FOUND
  line. The clarity subagent's fresh context has the same Read/Grep
  tools and can execute the same algorithm. The edit moves the
  algorithm's *execution* into the subagent while the algorithm's
  *documentation* stays in `SKILL.md` (because the plan-subagent
  path still uses it). Concretely: the clarity-prompt.md template
  gains a section that tells the agent "for each `spec_refs:` entry
  in the ticket frontmatter you just Read, resolve the anchor using
  this algorithm: …" with the same algorithm steps copied from
  `SKILL.md`. The duplication between SKILL.md and clarity-prompt.md
  for the algorithm body is acceptable — the algorithm is short
  (~7 lines) and the alternative (a third file the agent reads)
  buys nothing. The misleading `## Spec sections cited (verbatim,
  for cross-checking spec_refs)` heading drops out naturally with
  the `{{SPEC_REF_RESOLUTIONS}}` placeholder.
- **Engineering-rules block replaced with a Read pointer.** Today
  reviewer-prompt.md lines ~76–192 carry `§1 Surgical changes`,
  `§7 No defensive code for impossible scenarios`, and `§8 Test
  integrity` (with its Syntactic / Semantic / Authorization /
  Stack-specific / round-N must-shrink subsections), all verbatim
  from `engineering-rules-verbatim.md`. The edit replaces that
  block with a short instruction: "Before evaluating, Read
  `docs/process/engineering-rules-verbatim.md` — that file is the
  rule-text-of-record and you must apply every rule it carries,
  not just the ones you find convenient." Net effect: the reviewer
  loads ALL rules from the canonical file (94 lines), not just the
  curated subset the inline embed carried. That is the correct
  behavior; the previous subset-embed was an editing convenience,
  not a deliberate scoping. The round-N must-shrink rule (today at
  reviewer-prompt.md lines 149–162) is in engineering-rules-verbatim.md
  lines 75–80 — covered by the Read. The "Files budget and scope",
  "Out-of-scope", "Acceptance" sections that follow the rules block
  STAY inline because they interpret ticket frontmatter, not rules.
- **ID-verification guard.** One line inside each `## Template`
  block. For clarity-prompt.md, after the existing "The ticket is:
  {{TICKET_ID}}" line: "Before evaluating anything else, verify
  the ticket file you Read has `id: {{TICKET_ID}}` in its YAML
  frontmatter. If the frontmatter ID does not match, abort the
  review and return CLARITY VERDICT: FAIL with a single BLOCKERS
  line citing the mismatch." For reviewer-prompt.md, the analogous
  text with "return VERDICT: MANUAL" and UNCERTAINTY instead.
  The grep-able `{{TICKET_ID}}` substitution count goes from 1 to
  ≥ 2 per file — that is the acceptance signal.

## Big-picture notes

- **The structural win is verifiable by inspection, not by
  instrumented numbers.** A synthetic re-run of `/m1-tick start
  M1-005` (after refining M1-005's files_budget to include the
  three test files it adds — that ticket's `files_budget: 7`
  miscounts because it lists 7 production files without budgeting
  the 3 test files in `test_plan.adds:`, so it would clarity-FAIL
  on `FILES-BUDGET-PLAUSIBLE`) should produce a much smaller
  Agent prompt payload. The reviewer can read the
  pre/post diff stats on the prompt template itself to confirm.
  Three stacking wins per start: (a) ticket body moves to a path;
  (b) spec_refs resolution moves into the subagent so the main
  session no longer Reads cited spec files at all; (c) verdict
  reply collapses to a header + counts on the common APPROVE/PASS
  path. Per review: (a) ticket body, diff, and test-log all move
  to paths; (b) the ~85-line engineering-rules embed collapses to
  a Read instruction; (c) verdict reply collapses similarly.
- **The substitution change does NOT alter what the subagents
  see.** A subagent that loads the ticket via Read sees the same
  bytes the inlined substitution would have given it. So clarity
  verdicts and review verdicts are unchanged in content; only the
  *transport* changes. This is the load-bearing invariant — the
  reviewer subagent's verdict semantics, the must-shrink check,
  the engineering-rules application all stay identical.
- **Why Write at the agent**, not "agent prints, skill captures
  stdout to file". Agents return their reply as one structured
  message; "capture stdout" is not a primitive the agent harness
  exposes for cleanly extracting an inline verdict block. Giving
  the agent the Write tool, plus a prompt-supplied path, is the
  documented mechanism. The agent writes once, returns the short
  summary; the skill reads the path. Simpler than parsing a
  multi-section verdict out of the chat reply.
- **`target/` is not in .gitignore as of M1-009.** That matters
  for the test log path which has existed for several tickets;
  the operator's `target/` accumulates workflow artifacts that
  do not enter version control because everything under `target/`
  is excluded by Maven convention and the file references this
  ticket adds follow the same convention. No `.gitignore` edit
  needed.
- **The substitution change must not regress the "fresh context"
  invariant.** The clarity and review subagents have no
  conversation history; everything they need is in the prompt
  + tools. Path substitution preserves this — the agent reads
  what it needs and returns. The risk is the agent failing to
  Read the path and hallucinating instead. Mitigated by: (a)
  explicit instruction in the prompt template ("Read this path
  before evaluating anything"), (b) the persona description in
  the agent file reinforces it, (c) the verdict file the agent
  writes is the audit trail — if it wrote a verdict based on a
  ticket it didn't actually load, the bullets-cite-acceptance-
  items check will flag content mismatch.

## Out-of-scope expansion

- **Threat-actor / `/redteam`.** `docs/process/redteam-prompt.md`
  has a `{{DIFF_OUTPUT}}` substitution too, but the `/redteam`
  skill is intentionally decoupled from `/m1-tick` per
  `CLAUDE.md` §M1 workflow ("threat-actor … fresh-context
  adversary subagent … invoked via the separate `/redteam`
  skill"). The same template-slimming pattern is a candidate
  follow-up for `/redteam` once a flagged-ticket invocation
  reveals the cost. Not bundled here so this ticket stays
  focused on the always-running clarity-and-review path.
- **Plan subagent.** `docs/process/plan-prompt.md` fires only
  on `complexity: high` tickets at `start`. None of the M1
  tickets to date have shipped `complexity: high` at the time
  of writing (M1-007 umbrella is the obvious future candidate
  but is split into subtickets each medium-or-lower). So the
  Plan substitution pays nothing per-invocation today. A
  follow-up can slim it once a `complexity: high` ticket is
  in flight.
- **`docs/process/workflow.md`.** The universal workflow doc
  describes WHAT the reviewer receives ("the ticket file", "the
  diff", "the test output"), not the wire-format. The change
  here is procedural (path vs content) and does not alter that
  description. Leave workflow.md untouched.
- **`docs/process/engineering-rules-verbatim.md`.** The canonical
  rules file itself stays untouched. The ticket REMOVES the
  duplicate inline copy from `reviewer-prompt.md` and replaces it
  with a Read pointer, finally realising the canonical file's
  "single editing source" intent. The rules file is in the
  `spec_refs:` list anchored at its H1 for traceability; no edit
  is made to the rules file itself.
- **`docs/process/ticket-template.md`.** Ticket shape is unchanged.
  The substitution change is at the prompt layer, not the ticket
  layer.
- **Verdict semantics.** PASS/WARN/FAIL for clarity and
  APPROVE/REWORK/MANUAL/OVERRIDE-APPROVE for review remain
  unchanged. Only the return channel splits (full to disk, short
  to chat).
- **The SKILL.md split into per-subcommand files.** That is the
  separate ticket M1-011, which builds on this one's edits
  inside the `## start <id>` and `## review <id>` sections.
  Doing it here would conflate two structural changes in one
  diff.
- **STATUS.md content.** Regenerator output only; the regen step
  in `## commit <id>` is untouched.
- **Other ticket files** under `docs/plan/m1/tickets/`. Don't edit
  M1-001..M1-009. M1-005's mis-budgeted `files_budget: 7` is a
  real bug noted above but is M1-005's to fix on its next
  `/m1-tick start` attempt (refine path), not this ticket's
  scope.
- **Repo source code, poms, application.properties, migrations,
  tests.** This is a process-docs-and-skill diff only. Any pom
  or Java or SQL edit is scope drift.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The suite
  is currently green at M1-001..M1-003 level; `mvn verify` is a
  smoke check, not a behavioral assertion of these edits. The
  acceptance criteria are checked by grep against the edited
  files and by inspection of the post-edit substitution paragraphs
  in SKILL.md.)

## Alternatives considered

- **Alt A: split into three tickets, one per leak (clarity-prompt
  + clarity-verdict + reviewer-prompt + reviewer-verdict).** Rejected
  because the four edits share the same shape (replace `{{X_CONTENT}}`
  with `{{X_PATH}}`, add `{{VERDICT_FILE_PATH}}`, update the agent's
  Read+Write contract) and the skill-side substitution code touches
  the same two `## start <id>` / `## review <id>` paragraphs. Four
  separate tickets would quadruple lifecycle overhead with no
  isolation benefit. M1-002 set the precedent of bundling
  three related skill-procedure fixes in one ticket; this is the
  same shape.
- **Alt B: keep `{{TICKET_FILE_CONTENT}}`, only change the verdict
  return.** Rejected: the prompt-payload-sent-to-Agent cost is the
  larger of the two halves (the inlined ticket body is 10–20 KB
  per invocation; the verbose verdict reply is 2–5 KB). Doing only
  the verdict half leaves the bigger leak in place.
- **Alt C: have the agent stream the verdict to stdout and the skill
  parse it from the conversation transcript.** Rejected because the
  `Agent` tool returns one reply message; there is no clean stream
  to a side-channel. The Write-to-disk approach is the documented
  pattern and gives a durable audit file as a side benefit.
- **Alt D: keep the inlined diff, just slim the ticket content.**
  Rejected: on tickets with many files (M1-005's ~7 file diff is
  modest; M1-007 umbrella subtickets will be larger), the diff is
  the dominant cost. Path-substitution is uniformly applicable to
  ticket-body, diff, and test-output, so apply it consistently.
- **Alt E: also slim `redteam-prompt.md` and `plan-prompt.md` for
  consistency.** Rejected for scope reasons enumerated in
  "Out-of-scope expansion" — different skills (`/redteam`),
  different trigger conditions (Plan only on `complexity: high`),
  different cost profile. Bundle each with its own future ticket.
- **Alt F: split spec_refs-in-subagent + rules-by-Read + ID-guard
  into three separate follow-up tickets.** Rejected: each addition
  touches the same five files this ticket already edits
  (`clarity-prompt.md`, `reviewer-prompt.md`, the two agent
  definitions, `SKILL.md`); splitting would force three reviewer
  rounds on near-identical diffs and triple the lifecycle overhead
  with no isolation benefit. The decision mirrors Alt A's reasoning
  for the original four-leak bundle. The `files_budget: 5` and
  `files_scope` list are unchanged by the additions — they were
  always the right set of files for the substitution rewiring,
  and the additions exercise the same files.
