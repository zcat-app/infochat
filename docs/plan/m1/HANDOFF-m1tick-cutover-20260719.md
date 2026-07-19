# Handoff — m1-tick flow cutover + M1-648 rebuild (2026-07-19)

Authoritative continuation record for the m1-tick process rework and the
M1-648 teardown/rebuild. Read this first before touching either.

Repo state at handoff: **`main` is clean, everything committed, nothing
pushed.** Prod/test stacks are down (see auto-memory). Push is the user's call.

---

## 1. What shipped this session (5 commits on `main`, newest first)

| SHA | Subject |
|---|---|
| `79aef828` | process: cutover 3/3 — reviewer becomes a correctness gate; files_budget + must-shrink go advisory |
| `bd5f4ec4` | process: cutover 2/3 — resolve M1-662, slim escalation registries to a scalar |
| `8cc58cf4` | process: cutover 1/3 — replace clarity-reviewer subagent with linter + dev self-check |
| `1a5d6409` | process: preserve M1-648 redteam audits ahead of branch teardown |
| (before) `cb0284f6` | prior head; the cutover sits on top of it |

All five are `process:`-prefixed, doc/script only, fully inert to `mvn verify`,
and each is independently `git revert`-able. Verification performed: both
`scripts/lint-ticket.py` and `scripts/regen-status.py` parse and run green on
the full 697-ticket corpus; a synthetic escalated ticket confirmed the new
`escalation_reason` path renders; full cross-reference grep sweep found no
dangling pointer to the deleted files.

### The new flow in one screen

- **`/m1-tick start`** — no more clarity subagent. Step 1 is (1a) run
  `scripts/lint-ticket.py` (3 mechanical BLOCKERs: unresolved `spec_ref`,
  empty `out_of_scope`, unresolved load-bearing ticket-ID; everything else
  advisory) then (1b) a developer self-check in the main session's own
  context (implementable-as-written; ticket-vs-code truth; live census
  re-run). A lint BLOCKER refuses the start (fix the file, re-run — no
  escalate cycle); a genuine ambiguity → one `AskUserQuestion`. Step 0 now
  also runs `git worktree list`.
- **`/m1-tick review`** — hard-fails only on correctness/boundary
  (acceptance, test-integrity, spec-conformance, assertion-adequacy,
  `out_of_scope`, `files_scope` membership). Numeric `files_budget` overage
  and round-N must-shrink are **advisory WARNs**, not FAILs.
- **`/m1-tick escalate`** — reads one `escalation_reason:` scalar (set on
  escalate, cleared on resolution; survives cold resume). `escalations:` /
  `revisions:` are no longer frontmatter — history is git log. No
  `clarity-fail` reason exists anymore.
- Deleted: `docs/process/clarity-prompt.md`, `.claude/agents/clarity-reviewer.md`.
  The spec-anchor-resolution algorithm now lives in `docs/process/workflow.md`
  §"Spec-anchor resolution (canonical)".

### New linter checks (all in `scripts/lint-ticket.py`)

`OUT-OF-SCOPE-PRESENT` (empty → BLOCKER), `FORWARD-REFERENCE-RESOLVABLE`
(unresolved load-bearing ID → BLOCKER, prose → WARN), `SECURITY-FLAG-INFERENCE`
(files_scope hits a security surface but `security_relevant: false` → WARN —
this is the mechanism that widens `/redteam` reach), `CENSUS-PRESENT-IF-CLASS-SCOPED`
(WARN). Plus the pre-existing `SPEC-REFS-RESOLVABLE`, `FILES-SCOPE-COVERAGE`,
`PROSE-VERB-IN-VERIFY`.

---

## 2. M1-648 — torn down, NOT yet rebuilt (the main remaining work)

### What happened

M1-648 ("Semantic command-intent index with deterministic answer composition")
reached round-4 APPROVE, then `/redteam` found a **medium INJECTION**: help
delivery appended command-usage text to the chat reply **after** the sanitizer
ran (`ChatAgent:472-474`), and the **LLM elected** whether it fired and which
privileged command's syntax it carried (`collectHelpBlock`, `ChatAgent:669`).
For a bot-admin caller the appended block could be the real, copy-pasteable
`/grant-admin` usage — the exact social-engineering surface the sanitizer
exists to close. The remediation patch (admin-tier gate in `HelpLookupTool`)
failed again. Conclusion: **this is a design defect, not sloppiness — the
delivery model cannot be patched safe; it must be deterministic end-to-end.**

### Actions taken

- The two redteam audit files are **preserved on `main`**:
  `docs/plan/m1/redteam/M1-648-2026-07-19.md` (CLEAN, pre-fix diff) and
  `docs/plan/m1/redteam/M1-648-2026-07-19-r2.md` (FINDINGS, the injection).
  **These are the design input for the rebuild — read them first.**
- The `m1/M1-648-*` branch and its worktree were destroyed (the 25-file sloppy
  implementation is gone). Stale worktrees `M1-652` and `M1-653` were also pruned.

### ⚠ Hazard: M1-648 is still `status: pending` and RUNNABLE

Its `blocked_by` (M1-645/646/647/654) are all `done`, so `/m1-tick next` will
offer M1-648 and a `/m1-tick start` would restart the flawed design. **Before
any other m1-tick work, decompose it** (below), which sets it
`abandoned: decomposed`. Until then, do NOT `start` it.

### The rebuild plan (agreed with the user; NOT yet filed)

Decompose M1-648 into three tickets. **Next free IDs: M1-663, M1-664, M1-665**
(current max is M1-662; re-verify with `ls docs/plan/m1/tickets/M1-*.md` before
allocating — a concurrent session may have claimed one). File them with the
**new** ticket template (`docs/process/ticket-template.md`) and run
`scripts/lint-ticket.py` on each until clean before `start`.

**M1-663 — spec amendment (prerequisite).**
Amend `docs/spec/security.md` §"LLM output sanitizer" (heading at line ~315) to
state the delivery-ordering contract the injection finding exposed: any
command-usage / help text delivered into a **chat-mode reply** must be
*deterministic end-to-end* — deterministic code decides both **whether** it is
delivered and **what** it contains, driven by the parsed user request, never by
a model-elected tool call — OR it passes through the sanitizer like any other
LLM-authored output. The current exemption ("deterministic command output")
must be tightened so "only the bytes are deterministic while the emission
decision is the model's" no longer qualifies. `spec_amend_for:
docs/spec/security.md §LLM output sanitizer`, `spec_amend_parent: M1-665`.
Spec-only, small.

**M1-664 — Ticket A: the semantic command-intent index + retrieval (the safe 80%).**
Everything in the original M1-648 EXCEPT the LLM-facing delivery/append: the
`V60__doc_embedding` migration + provider-only grants, the startup-built
intent index with content-hash staleness detection, and a tier-filtered
`helpLookup`-style retrieval that returns a command NAME (match-not-assert
invariant, tier-filter-before-return). The redteam CLEAN audit covered exactly
this retrieval surface — it was sound; only the delivery append failed. Carry
over M1-648's acceptance items 1–6 and the allowlist/parity/advertising items
(spec table row, `TOOL_INSTRUCTIONS`, `everyRegisteredToolIsAdvertised`,
`ChatToolRegistryTest` authorized change, `CommandIntentSynonyms` reachability).
`decomposed_from: M1-648`. `complexity: high`, `risk: high`,
`security_relevant: true`, `migration_touch: true`.

**M1-665 — Ticket B: deterministic delivery/composition path (the security-hard part).**
How a matched command's usage reaches the user, honoring the amended spec:
deterministic code — not a model-elected append — decides whether and what to
deliver, so no post-sanitizer LLM-elected path can carry privileged command
syntax. Small, `security_relevant: true`. `decomposed_from: M1-648`,
`blocked_by: [M1-663, M1-664]`. **Redteam-first** (run `/redteam` before review,
as `run.md` step 4 now does for security_relevant tickets).

**Then set M1-648** → `status: abandoned`, `abandoned_reason: decomposed`,
record `M1-664`/`M1-665` (and `M1-663`) in `deferred_on:` for lineage. (The
`/m1-tick escalate → 3 decompose` path automates most of this, but M1-648 is
`pending` not `escalated`, so either escalate it first or hand-edit the
frontmatter + create the three files directly, then `scripts/regen-status.py`.)

Original M1-648 body (full retrieval design, census, the D66 decision-renumber
note, threshold-calibration guidance, package-boundary notes) is preserved at
`docs/plan/m1/tickets/M1-648-semantic-command-intent-index.md` — mine ticket A's
acceptance from it.

---

## 3. Pending queue after the cutover

`scripts/regen-status.py` counts at handoff: pending=4, done=674, abandoned=18,
everything else 0. Runnable pending: **M1-642** (per-category digest delivery)
and **M1-648** (see hazard above — decompose, don't start). Also pending:
**M1-649** (conceptual help topics; naturally follows the M1-648 rebuild) and
**M1-652** (gap-filling digest redelivery). M1-662 is `abandoned: superseded`
(its registry contradiction was resolved by cutover 2/3).

---

## 4. What was explicitly NOT done, and why

- **The M1-648 rebuild tickets (M1-663/664/665) were not filed.** They are
  feature-authoring work; §2 has the full plan and IDs so any session (or tool)
  can file them directly.
- **No reviewer-mandate rewrite.** Cutover 3/3 makes the reviewer a correctness
  gate by *subtracting* the bookkeeping hard-gates, not by adding a new mandate
  — deletion-first, and it leaves the working correctness checks untouched.
- **No incidental-fix commit-trailer channel.** Considered, dropped: it is an
  addition, against the deletion-first doctrine, and lower priority than the
  gate removals.
- **Nothing pushed.** All five commits are local on `main`.

---

## 5. Gotchas for the next operator

- The cutover is docs/scripts only — `mvn verify` covers none of it. The real
  gates were: run both scripts against the corpus + grep-sweep cross-references.
  Re-run `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-*.md` and
  `python3 scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' docs/plan/m1/STATUS.md`
  after any process edit.
- `regen-status.py` now reads the `escalation_reason` scalar; a stray
  `escalations:` block in an old done ticket (M1-600..611) is skipped as an
  unknown key — those tickets are intentionally left byte-untouched.
- The five cutover commit messages are detailed (the incident → gate → tradeoff
  reasoning for each change). Read them for the "why" behind any rule you're
  tempted to revert.
