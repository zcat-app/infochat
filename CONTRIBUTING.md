# Contributing to infochat

Thanks for wanting to improve infochat. This guide covers **how a change is made
here** — the conventions, the workflow, and a worked example.

You'll need a working development environment first (build from source, run the
services, run the tests) — that's in **[DEVELOPER.md](DEVELOPER.md)**.

---

## Before you start

infochat is **ticket-driven**, and during the v1 build that lifecycle is largely
automated. Before changing anything, read:

- **[docs/process/workflow.md](docs/process/workflow.md)** — the universal ticket
  lifecycle (start → implement → `mvn verify` → review → commit → merge → escalate).
- **[docs/plan/m1/README.md](docs/plan/m1/README.md)** — M1-specific framing
  (ID prefix `M1-NNN`, branch token, status board) and a pointer table into every
  process document.
- **[docs/process/engineering-rules-verbatim.md](docs/process/engineering-rules-verbatim.md)**
  — the canonical engineering rules.
- **[CLAUDE.md](CLAUDE.md)** — the always-loaded summary of those rules plus the
  coding style.

## Conventions that bind every change

These hold whatever editor you use:

- **Surgical diffs** — every changed line traces to the change you're making;
  don't "improve" adjacent code.
- **No workarounds** — fix the code or escalate; never weaken or skip a test.
- **Run `mvn verify`** from the repo root before declaring a change done — not
  just your new tests.
- **Commit prefixes** — `M1-NNN:` for a tracked ticket (code/tests/migrations),
  `spec:` for a pure `docs/spec` or `docs/design` edit, `process:` for `.claude/`,
  `docs/process/`, `docs/plan/`, or `CLAUDE.md`.

> **About `/m1-tick`.** This is **how implementation is actually done in this
> repo** — a **Claude Code** skill that drives the lifecycle above and runs the
> clarity, code-review, and red-team subagents that are the whole point of the
> flow (the next section walks through it). It needs Claude Code with the repo's
> bundled skills. If you work with other tooling you can still follow the same
> conventions by hand (branch → surgical change → `mvn verify` → commit with the
> right prefix), but then the review and red-team gates are yours to reproduce —
> self-review against the engineering rules and the threat model rather than
> getting them for free.

## The flow step by step: adding a `/now` command

The key thing to understand: **you don't hand-write the change and open a pull
request.** You describe the change as a *ticket*, then drive it through
`/m1-tick`, which runs the implementation through two quality gates the
conventional "code it by hand" approach doesn't have — an automated **code
review** and, for security-sensitive work, an adversarial **red-team** pass.
Those gates are the point of the flow.

We'll add a trivial read-only command, `/now`, that replies with the current
date and time, and take it all the way from idea to merged.

> Everything below is run **inside Claude Code**, where `/m1-tick` and `/redteam`
> live. You'll need a working dev environment first — see
> [DEVELOPER.md](DEVELOPER.md). The illustrative ticket number `M1-900` is a
> stand-in; use the next unused number (check `docs/plan/m1/tickets/` and
> `docs/plan/m1/STATUS.md`).

### Step 0 — Start from a clean, green tree

```bash
git checkout main
mvn verify        # confirm the baseline is green before you change anything
```

### Step 1 — Write the ticket (describe the change; don't code it yet)

Every change starts as a ticket. Copy the template and fill it in:

```bash
cp docs/process/ticket-template.md docs/plan/m1/tickets/M1-900-now-command.md
```

Fill in at least `id`, `title`, and the **acceptance criteria** — one runnable
bullet per behavior. For `/now`:

- `/now` replies with the current UTC time as plain text.
- `/now` is allowed during slow-start probation (it's a harmless read).
- `/help` lists `/now`.

Also set `files_budget` (max files the change may touch) and `out_of_scope`. The
ticket *is* the spec for the change — the review gate later checks the diff
against exactly these criteria. Commit the draft:

```bash
git add docs/plan/m1/tickets/M1-900-now-command.md
git commit -m "M1-900: draft ticket"
```

### Step 2 — Confirm it's runnable

```
/m1-tick next
```

Lists every pending ticket whose blockers are all done. `M1-900` should appear.

### Step 3 — Start it

```
/m1-tick start M1-900
```

This runs a **clarity pre-flight** (is the ticket unambiguous and runnable?),
creates the branch `m1/M1-900-now-command` off `main`, and marks the ticket
`in-progress`. If clarity returns `FAIL`, tighten the acceptance criteria and
start again.

### Step 4 — Implement (within the ticket's scope)

There is deliberately no `/m1-tick` command for this step — implementation is the
work you (or Claude) do in the conversation between `start` and `review`, on the
branch `start` created. This is the interactive part of the flow: you discuss the
approach, watch the diff take shape, and **steer it** — ask for changes, point
out a missed case, course-correct — before the formal review gate runs. This is
where the code is written — guided by the acceptance criteria and the engineering
rules, touching **at most `files_budget` files** and nothing in `out_of_scope`.
For `/now` the implementation adds a new command handler in the provider, lets
probation users run it, lists it in `/help`, localizes the reply, and adds a
test. Staying inside the declared scope is what keeps the next step (the review)
honest — don't free-hand changes the ticket didn't ask for.

### Step 5 — Run the full test suite

```bash
mvn verify
```

The **whole** suite, not just your new test. It must be green before you ask for
review.

### Step 6 — Code review (the first gate)

```
/m1-tick review M1-900
```

Spawns the **code-reviewer subagent**, which checks the diff against the
engineering rules: scope drift, test integrity, anything touched outside scope,
and whether **every** acceptance criterion is actually met. It returns:

- **APPROVE** → go to Step 7.
- **REWORK** → fix only the named items, re-run `mvn verify`, then
  `/m1-tick review M1-900` again.
- **MANUAL** → `/m1-tick escalate M1-900` (see below).

### Step 7 — Commit

```
/m1-tick commit M1-900
```

Only after an `APPROVE`. It makes one commit on the ticket branch with the
`M1-900: …` subject and a `Reviewed-by:` trailer recording the verdict, and marks
the ticket `done`. Nothing is on `main` yet — that happens at Step 9.

### Step 8 — Red-team (the security gate, when it applies)

For a change marked `security_relevant: true`, run an adversarial review on the
committed branch, **before** you merge:

```
/redteam M1-900 --in-progress
```

The `--in-progress` flag is required here: the commit lives on the ticket branch
but isn't on `main` yet, so red-team audits the branch tip (`main...branch`).
Without the flag it searches `main` for the ticket's commit, doesn't find it, and
refuses.

A **threat-actor subagent** reads the project's threat model
([docs/spec/security.md](docs/spec/security.md)) plus the diff and flags gaps
between what the model promises and what the diff delivers. `/now` is harmless,
so here it's not required — but anything touching authorization, input parsing,
or outbound network calls **must** go through it. This adversarial pass is the
second advantage the flow gives you over coding by hand. If it surfaces a
finding, fix it on the same branch (or `/m1-tick escalate M1-900 redteam-finding`)
before merging.

### Step 9 — Merge

```
/m1-tick merge M1-900
```

Squash-merges the branch into `main` so history stays one-commit-per-ticket. It
never pushes — pushing remains your call.

### If you get stuck — escalate

```
/m1-tick escalate M1-900
```

Prints a five-way menu — **refine** (the ticket was ambiguous), **override** (the
reviewer was too strict), **decompose** (split into several tickets), **defer**
(block on new work the change surfaced), **spec-amend** (the spec itself is
wrong). Pick the one that fits; the flow records the decision and adjusts state.

> Curious where command code actually lives before you write a ticket? Read a
> couple of existing handlers — `GetSourcesCommandHandler` and
> `StatusCommandHandler` in
> `infochat-provider/src/main/java/app/zcat/infochat/provider/command/` — to see
> the conventions a `/now` handler would follow.
