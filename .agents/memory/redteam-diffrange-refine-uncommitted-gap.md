---
name: redteam-diffrange-refine-uncommitted-gap
description: "The /redteam single-ticket diff-range algorithm resolves to an EMPTY diff at every /m1-tick run pre-commit redteam gate (branch has 0 commits), and additionally false-matches a same-prefix refine commit when one exists; audit the working-tree diff vs fork instead."
metadata:
  type: feedback
---

Hit driving M1-624 through `/m1-tick run` (2026-07-15). When a `security_relevant`
ticket reaches `run` step 5 (redteam gate, which fires BEFORE commit) after having gone
through a **clarity-fail bounded self-refine**, the `/redteam <id> --in-progress`
diff-range algorithm resolves WRONG:

- **Step-1 merged-form** `git log --grep="^M1-NNN: " main` matches the **refine commit**
  (`M1-NNN: refine ticket spec (clarity-fail rework)`) that the self-refine landed on
  main — a false single-match → it would audit the ticket-file-only refine diff, not the
  code.
- **Step-2 branch-form** (`main...branch`) is **empty**: at the pre-commit redteam gate
  the implementation is still uncommitted in the working tree, so the branch has **0
  commits beyond main** (`git rev-list --count main..HEAD` = 0).

Neither native form captures the real change.

**RECURRED 2026-07-18 (M1-646), and the framing above is too narrow.** That run had
**no refine commit** — so step 1 returned 0 matches (not a false match) and step 2 was
still **empty** (`git rev-list --count main..HEAD` = 0). Conclusion: the step-2-empty
half fires at **EVERY** `/m1-tick run` redteam gate, refine or not, because commit runs
*after* redteam by design. The refine commit only adds the step-1 false-match on top.
Always run `git rev-list --count main..HEAD` before trusting either form; a `CLEAN`
verdict from an empty diff is the silent failure mode.
Also re-hit the inventory-noise trap below by grepping whole files (149 "auth" / 153
"ban" hits, nearly all `/invite`·`/ban` help copy in en+cs.properties) — the
added-lines advice is easy to skip under momentum. Apply it.

**RECURRED AGAIN 2026-07-18 (M1-651), third instance — now treat as certain, not likely.**
Same shape: 0 commits beyond main at the gate, so `main...branch` was empty. Confirms the
step-2-empty half is unconditional. Worked around by driving the whole redteam procedure
manually (build inventories → `m1-render-prompt.py` → spawn `threat-actor`) with
`DIFF_FILE_PATH` pointed at the working-tree-vs-fork-point diff, and by stating the range
deviation IN THE AGENT PROMPT ("do NOT re-derive with `git diff main...<branch>` — that
yields an empty diff"). The prompt-level warning matters: the agent will otherwise try to
re-derive the range itself and silently audit nothing. M1-651 ran this three times.

**Why:** the redteam skill's algorithm assumes either a merged (`done`) commit or a
branch with impl commits; it has NO working-tree form. The run-flow redteam gate runs on
an uncommitted tree (same state the code-reviewer audits).

**How to apply:** audit the actual implementation = the **working-tree code diff vs the
fork point**, the SAME diff the reviewer used:
`git diff $(git merge-base main HEAD) -- ':(exclude)docs/plan/m1/*' > target/redteam-diff-<id>.diff`;
set `BASE_REF=<fork sha>`, `HEAD_REF="working-tree (uncommitted ...)"`. Record the
deviation + reason in the audit file's `disposition:` and the ticket's `redteam_audits[].note`.
Also: scope the sensitive-surface inventory greps to the diff's **added lines**, NOT
whole files — whole-file grep over a big file (InboundRouter ~1200 lines) floods the
inventory with unchanged-line noise (45–64 KB); `.properties` files match all the
`/invite`·`/ban` help copy → restrict inventory to code files / added lines.

Related: [[concurrent-session-committed-to-my-branch]] (same "re-check git state at the
gate" discipline), [[m1-tick-workflow-cannot-nest-gates]].
