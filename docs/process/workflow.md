# Ticket-driven workflow specification

This document is the universal workflow specification — the lifecycle, ticket frontmatter, reviewer behavior, escalation, and commit conventions that apply to ticket-driven work across milestones. It is the single source of truth for the procedure.

The verbatim engineering rules and test-integrity rules the reviewer enforces live in [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md). That file is the editing source; this document references it rather than duplicating the rule prose. The reviewer prompt embeds it inline because the reviewer subagent runs in fresh context.

The always-loaded summary lives in `CLAUDE.md` §M1 workflow. Per-milestone framing (what's in scope this milestone, deltas from this universal workflow) lives in `docs/plan/<milestone>/README.md` (e.g. `docs/plan/m1/README.md`).

Precedence on conflict: `CLAUDE.md` summary < this document < [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md) (the rules themselves are canonical). If anything here contradicts `CLAUDE.md`, sync `CLAUDE.md`. If anything here contradicts `engineering-rules-verbatim.md`, this file is wrong.

> **Milestone tokens used below.** Examples use `M<N>` (e.g. `M1`, `M2`) for ticket-ID prefixes and `m<N>` (e.g. `m1`, `m2`) for branch and directory tokens. The currently active milestone is M1, driven by the `/m1-tick` skill. Future milestones may instantiate their own driver skill or extend the existing one.

---

## Lifecycle

```
   pending                              ┌─→ done
      │                                 │   (squash-merge into main)
      ▼                                 │
   in-progress  ───→  in-review  ───────┤
   (developer)        (reviewer)        │
                          │             │
                          ▼             ▼
                      escalated  ──→  refine | override | decompose | defer | spec-amend
```

Status values (used in ticket frontmatter):

| Status | Meaning |
|---|---|
| `pending` | Drafted, not yet started. May be `blocked_by` other tickets. |
| `in-progress` | Developer (the main conversation) is actively implementing. Branch exists. |
| `in-review` | Code is committed to the branch, `mvn verify` is green, reviewer subagent is running or has just returned a verdict. |
| `escalated` | Round cap hit, or an immediate-escalation trigger fired. Awaiting user resolution via the five-way menu. |
| `done` | Reviewer returned `APPROVE`, ticket commit landed on `main` via squash-merge. |
| `deferred` | Work paused. Either intentionally postponed (out-of-milestone scope discovered), blocked on a new ticket the work surfaced, or waiting on a spec amendment. |

A ticket may move between `in-progress` and `in-review` once (the round-2 rework — or twice when the ticket sets `round_cap: 3`). It may move to `escalated` from either side. It may move to `deferred` from any state.

---

## Ticket frontmatter

Every ticket file under `docs/plan/<milestone>/tickets/` starts with YAML frontmatter. The complete schema lives in [`ticket-template.md`](ticket-template.md); the load-bearing fields are documented below. Static fields are author-supplied; dynamic fields are populated by the milestone-driver skill (e.g. `/m1-tick`) and start empty.

```yaml
---
# --- Identity & lifecycle ---
id: M<N>-NNN
title: Short imperative title (≤ 60 chars)
status: pending             # pending | in-progress | in-review | escalated | done | deferred
created: 2026-05-09         # set on first save; never edited
last_updated: 2026-05-09    # auto-updated on every status transition
blocked_by: []              # ticket IDs that must be `done` before this can start

# --- Sizing & risk ---
files_budget: 8             # max files this ticket may touch (incl. tests). Breach → escalate.
complexity: low             # low | medium | high. high → spawn Plan subagent before implementing.
risk: low                   # low | medium | high. high → reviewer extra-strict, mandatory full-suite re-run on commit.
round_cap: 2                # default 2; opt-in to 3 ONLY for complexity:high or risk:high.
security_relevant: false    # true → triggers /redteam (threat-actor review skill)
migration_touch: false      # true → ticket touches Flyway migrations; serializes parallel start

# --- Scope ---
out_of_scope:
  - infochat-provider/**
  - migrations under V99__*
acceptance:                 # ideally runnable assertions, not prose
  - "mvn -pl infochat-collector test -Dtest=RssFetcherIT returns success"
  - "Flyway migration V003__source.sql applies cleanly on a fresh DB"
test_plan:
  adds:
    - infochat-collector/src/test/java/.../RssFetcherIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/schema.md §Sources and tags
decision_refs:
  - D38
  - D44

# --- Lineage (populated only when applicable) ---
decomposed_from:            # ticket ID this was split from
replaces:                   # ticket ID this rewrites (refine path)
replaced_by:                # set on the OLD ticket when refine produces a new one
deferred_on:                # ticket ID this ticket is blocked on
deferred_reason:            # decomposed | blocked-on-new-ticket | spec-amend | out-of-scope
spec_amend_for:             # set on a ticket whose purpose is to amend the spec for a paused parent
spec_amend_parent:          # the parent ticket waiting on this spec amendment

# --- Dynamic (populated by the driver skill; start empty) ---
reviews: []                 # {round, date, verdict, checks, diff_stats}
escalations: []             # {date, reason, reviewer_verdict_excerpt}
revisions: []               # populated on `refine` escalations
overrides: []               # populated on `override` escalations
redteam_findings: []        # populated by /redteam
clarity_check: {}           # populated by the driver's `start` (pre-flight)
---
```

Body sections (in this order):

1. **Context** — one paragraph: why this ticket exists, what it unlocks.
2. **Definition of Done** — bulleted, testable. Mirrors `acceptance` but in plain language.
3. **Implementation notes** — non-binding hints. Pointers to relevant code, not a step-by-step.
4. **Big-picture notes** — what the implementer must keep in mind that isn't in the immediate diff. E.g., "this fetcher will later be one of N kinds; design the SPI so kind 2 doesn't need to retrofit it."
5. **Out-of-scope expansion** — prose explanation of what `out_of_scope` covers and why.
6. **Authorized test changes** — list of pre-existing tests this ticket modifies and the new expected behavior. Required when modifying any pre-existing test (see canonical rules §8 test-modification authorization). Otherwise: "(none — this ticket adds tests but does not modify existing ones)".
7. **Alternatives considered** — if relevant. Discourages re-deriving the same alternatives during implementation.

---

## The flow (the milestone-driver skill orchestrates this)

The active skill is [`/m1-tick`](../../.claude/skills/m1-tick/SKILL.md) for milestone M1. Future milestones may instantiate their own driver. Steps below describe the procedure; the skill applies it.

### 0. Pick a ticket — `/<driver> next`

The skill reads `docs/plan/<milestone>/tickets/`, finds tickets where `status: pending` AND every entry in `blocked_by` has `status: done`, and prints the runnable list ordered by ID. The user picks one.

### 1. Start — `/<driver> start M<N>-NNN`

- **Ticket-clarity pre-flight.** Spawn a fresh-context subagent with the prompt at [`clarity-prompt.md`](clarity-prompt.md). It validates the ticket itself: are acceptance criteria runnable, is `out_of_scope` non-empty, do `spec_refs` resolve to real anchors in `docs/spec/`, is `files_budget` plausible given the acceptance criteria. Result is recorded under `clarity_check:` in frontmatter.
- If clarity returns `FAIL`, the start is blocked and the user is prompted to refine the ticket before implementation begins. `WARN` is informational and does not block.
- Set frontmatter `status: in-progress`. Update `last_updated`.
- Create branch `m<N>/M<N>-NNN-<slug>` off `main`.
- If `complexity: high`, spawn `Agent(subagent_type: "Plan")` with the ticket body and require an implementation outline before any code is written.
- The main conversation IS the developer from this point. No developer-subagent.
- Regenerate `STATUS.md`.

### 2. Implement

- Touch only files within `files_budget` and outside `out_of_scope`. Approaching the budget → escalate before exceeding it.
- Match existing style. No adjacent improvements (CLAUDE.md §Surgical changes).
- If a better alternative surfaces → record under `Alternatives considered:` in the eventual commit message; complete the ticket as written.

### 3. Test — `mvn verify` from repo root

- Run the **full** suite, not just the new tests.
- If anything fails:
  - Fix the code. Never the test, unless the test itself was the change requested by the ticket.
  - Two consecutive failures with the same root cause → escalate (loop indicator).
  - Failure mode that suggests the ticket's premise is wrong (e.g., a spec invariant breaks no matter what) → escalate.

### 4. Review — `/<driver> review M<N>-NNN`

- Skill spawns `Agent(subagent_type: "code-reviewer")` with the prompt from [`reviewer-prompt.md`](reviewer-prompt.md).
- Reviewer receives:
  - The ticket file.
  - The diff (`git diff main...HEAD`).
  - The list of files in `files_budget` that were NOT touched (the "negative space"), so the reviewer can judge whether the un-touched files were a deliberate skip or a forgotten part of the scope.
  - The test output.
  - On round 2: the round-1 diff stats (files touched, lines added, lines removed) for the must-shrink check.
  - The verbatim engineering rules and test-integrity rules embedded inline (the reviewer subagent has no other context).
- Reviewer returns the structured verdict (see "Reviewer verdict format" below).
- Set frontmatter `status: in-review`.

### 5. Resolve

| Verdict | Action |
|---|---|
| `APPROVE` | Proceed to commit (step 6). |
| `REWORK` (round 1) | Address only the named items. Do not re-architect. Re-run `mvn verify`. Re-invoke reviewer. |
| `REWORK` (round 2) | Escalate, unless the ticket sets `round_cap: 3` AND the round-2 diff actually shrank vs round 1. With `round_cap: 3`, allow one more rework round; otherwise no round 3. |
| `MANUAL` | Escalate immediately. The reviewer's uncertainty is not for the developer to resolve. |

**Round-2 must-shrink.** Round 2 is a fix-only round. The reviewer compares round-2 diff stats to round-1 and fails `SCOPE-DRIFT-CHECK` if the round-2 diff is larger along files-touched, net lines added, or net lines removed — unless the round-1 REWORK explicitly required a refactor that grows the diff and the developer cited that REWORK item in the round-2 commit message.

### 6. Commit — `/<driver> commit M<N>-NNN`

- **Safety re-run before committing.**
  - For tickets with `complexity: high` OR `risk: high`: the commit subcommand re-runs `mvn verify` from the repo root. The commit only proceeds on success. This closes the "skipped tests, faked review" gap for the tickets where the cost is highest.
  - For all other tickets: the commit subcommand checks that the most recent test log (`target/<driver>-test-{ID}-r*.log`) has an mtime newer than the latest mtime among the staged files. If the log is older than any staged file, refuse and require a fresh `mvn verify`.
- One commit on the per-ticket branch.
- Subject: `M<N>-NNN: <imperative summary>` (≤ 72 chars).
- Body: the Context paragraph from the ticket + any `Alternatives considered:` trailer.
- `Reviewed-by:` trailer carrying the reviewer's `APPROVE` verdict line and the reviewer agent's run identifier (best-effort).
- Set frontmatter `status: done`. Update `last_updated`.
- Squash-merge into `main` is the user's call (so `main` history stays one-commit-per-ticket).

### 7. Escalate — `/<driver> escalate M<N>-NNN`

The skill prints the five-way menu in chat:

```
M<N>-NNN: <title>  —  ESCALATED
Trigger: <reason: round-cap | manual-verdict | budget-breach | premise-fail | loop | redteam-finding>

Reviewer's last verdict:
  <verbatim block>

Choose:
  1. refine     — acceptance criteria were ambiguous; rewrite the ticket
  2. override   — reviewer was too strict; record the override and approve
  3. decompose  — split into N tickets; defer this one and queue replacements
  4. defer      — block on a new ticket the work surfaced; pause this one
  5. spec-amend — the spec itself is wrong; raise an amendment ticket and pause

Reply with: <number> [optional notes]
```

- `refine` → user edits the ticket; status returns to `in-progress`. The original frontmatter is preserved in a `revisions:` list with the date and a one-line reason.
- `override` → reviewer's specific objections are recorded under `overrides:` with a one-line user justification. Status returns to `in-review` and the skill proceeds to commit.
- `decompose` → user names the replacement ticket IDs. Original ticket → `status: deferred` with `deferred_reason: decomposed`. Replacement skeletons created in `docs/plan/<milestone>/tickets/` with `decomposed_from: M<N>-NNN` populated. The lineage is queryable so a stale child doesn't get lost when its parent is later reopened.
- `defer` → user names the blocking ticket ID (or asks the skill to draft it). Original → `status: deferred` with `deferred_on:` and `deferred_reason: blocked-on-new-ticket`. Blocker → new pending ticket.
- `spec-amend` → the spec itself is wrong. The skill creates a new ticket whose acceptance criteria amend the spec section, with `spec_amend_for: <spec-path-and-section>` and `spec_amend_parent: M<N>-NNN`. The original ticket → `status: deferred` with `deferred_reason: spec-amend` and `deferred_on:` pointing at the new amendment ticket. Use this instead of `decompose` whenever the issue is "the spec said X but should say Y", not "the implementation needs to be split into N pieces."

---

## Reviewer verdict format

The reviewer subagent MUST return a single message with this structure:

```
VERDICT: <APPROVE | REWORK | MANUAL>

SCOPE-DRIFT-CHECK: <PASS | FAIL>
  <one paragraph: which changed lines do not trace to acceptance criteria
   or files_budget, or PASS if all do. On round 2, also FAIL if the diff
   grew along files-touched, lines added, or lines removed and round-1
   REWORK did not authorize a refactor.>

TEST-INTEGRITY-CHECK: <PASS | FAIL>
  <one paragraph: any forbidden patterns introduced (see canonical rules
   §8 in engineering-rules-verbatim.md, embedded inline in the prompt),
   or PASS if none>

OUT-OF-SCOPE-CHECK: <PASS | FAIL>
  <one paragraph: any file or path in `out_of_scope` was touched, or PASS>

NEGATIVE-SPACE-CHECK: <PASS | WARN>
  <one paragraph: list of files in `files_budget` that were NOT touched.
   PASS if every untouched file is plausibly a deliberate skip; WARN if
   any look like forgotten parts of the scope. WARN does not force
   REWORK but the reviewer flags it for the user.>

ACCEPTANCE-CHECK: <PASS | PARTIAL | FAIL>
  <one bullet per acceptance item, with PASS/FAIL/SKIPPED and a one-line
   reason>

REWORK ITEMS: (omit on APPROVE; required on REWORK)
  1. <specific, addressable, scoped to existing diff>
  2. ...

UNCERTAINTY: (required on MANUAL; omit otherwise)
  <what is unclear, what the options are, why this can't be auto-resolved>
```

Verdict rules:
- Any `*-CHECK: FAIL` forces `VERDICT` to be at least `REWORK`. `APPROVE` requires every check to be `PASS` (`NEGATIVE-SPACE-CHECK: WARN` is permitted under `APPROVE` and is surfaced to the user as informational).
- `MANUAL` is for genuine reviewer uncertainty (ambiguous spec, conflicting rules, no clear path). Use sparingly.
- `REWORK ITEMS` must be specific and addressable in the existing diff. "Refactor X for clarity" is too vague; "rename `Foo.bar()` to `Foo.baz()` to match §1.4 of the spec" is fine.

---

## Test-integrity rules (no shortcuts; the reviewer enforces these)

The full forbidden-pattern list — syntactic, semantic, test-modification authorization, stack-specific (Postgres+pgvector), and round-2 must-shrink — lives in [`engineering-rules-verbatim.md`](engineering-rules-verbatim.md) §8. That file is the editing source; the reviewer prompt embeds its text inline so the fresh-context subagent sees it without external reads.

A `FAIL` on `TEST-INTEGRITY-CHECK` is never `REWORK`-able by the developer alone. The reviewer escalates to `MANUAL` if the developer's stated rationale is "this is fine because ...". The user is the only one who can override test-integrity violations.

---

## Commit conventions

```
M<N>-NNN: <imperative summary, ≤ 72 chars>

<Context paragraph from the ticket: why this ticket exists, what it
unlocks. Wraps at 72 chars.>

<If alternatives were considered:>
Alternatives considered:
  - <alt 1>: <one-line reason rejected>
  - <alt 2>: ...

Reviewed-by: code-reviewer (VERDICT: APPROVE; agent run: <id-or-NA>)
```

- `git push` is the user's decision, not the agent's. The skill stops at the local commit.
- `git revert <commit>` cleanly undoes a ticket because there is one commit per ticket on `main`.
- `git bisect` becomes ticket-bisection for the same reason.
- Never `--amend` a passed commit. Defects → new ticket → new commit.

---

## Parallelism

Default: sequential. One `in-progress` ticket at a time.

Parallel allowed when:
- Two `pending` tickets have empty intersection on `files_budget` (no shared paths).
- Two `pending` tickets have empty intersection on `out_of_scope` exclusions.
- Neither has `migration_touch: true` AND no in-flight ticket has `migration_touch: true` (migrations serialize globally; the flag makes the rule mechanically checkable).
- The user explicitly opts in via `/<driver> start <id> --parallel`.

If parallel, each ticket runs in a git worktree (`Agent(isolation: "worktree")`). The skill refuses to start a parallel ticket whose constraints overlap an in-flight ticket; the conflict is surfaced via `STATUS.md`.

---

## Red-team (threat-actor) review

Adversarial security review running in fresh context. The procedure is its own skill — see [`.claude/skills/redteam/SKILL.md`](../../.claude/skills/redteam/SKILL.md). Prompt template lives in [`redteam-prompt.md`](redteam-prompt.md). Invoked via `/redteam <ticket-id | milestone <name> | id-range <a..b> | release <tag>>`.

**When it runs:**
- At a milestone boundary, when all tickets in a milestone phase reach `done`.
- On any single ticket flagged `security_relevant: true`, after that ticket's normal review passes APPROVE.
- Before tagging a release.

**What the subagent sees:**
- `docs/spec/security.md` (the threat model — what the system promises to defend against).
- The cumulative diff across the tickets being red-teamed (`git diff <base>...<head>`).
- The list of authentication, authorization, input-validation, and ban-handling code paths touched.

**What it does NOT see:** the implementation rationale, the design notes (`docs/design/**`), the ticket bodies. The adversary is looking for the gap between spec promise and shipped delivery; reading the rationale would anchor it on the implementer's mental model.

**Verdict format:**

```
RED-TEAM VERDICT: <CLEAN | FINDINGS>

FINDINGS: (omit on CLEAN; one entry per finding)
  - CATEGORY: <AUTH-BYPASS | INFO-LEAK | INJECTION | DOS | PERM-ESCAL | AUDIT-EVASION>
    SEVERITY: <critical | high | medium | low>
    PROMISE: <what the spec says the system defends against>
    GAP: <how the diff fails to deliver the promise>
    REPRO: <concrete attack sequence the adversary would run>
    SUGGESTED-FIX-CLASS: <one of: input-sanitization | trust-boundary-tightening |
                          missing-auth-check | rate-limit | audit-log-coverage | other>

OUT-OF-MODEL: (optional)
  - <attacks that look juicy but fall outside the documented threat model;
    flag them so the user can decide whether to extend the model>
```

**What happens with findings:** Findings are NOT auto-converted to REWORK. They escalate to the user with the five-way menu (trigger reason: `redteam-finding`) and `redteam_findings:` populated in the relevant ticket. The user decides which findings warrant new tickets, which warrant a spec amendment (the `spec-amend` escalation path), and which fall outside the model.

---

## What is not in this workflow

- **No automated push.** The user reviews and pushes.
- **No automated PR creation.** Per-ticket branches are local; the user opens PRs at their cadence (one PR per ticket is the natural shape).
- **No CI definition yet.** The reviewer + `mvn verify` IS the gate. CI mirrors of the same gate land in a later milestone.
- **No metrics.** "How long did this ticket take?" is git-log-derivable; no separate tracker.
- **No external trackers.** GitHub Issues is fine for *user-facing* bug reports later; ticket-as-code is the dev-facing source of truth.
- **No automated red-team on every ticket.** Cost-bounded by milestone-end + flagged-ticket triggers.
