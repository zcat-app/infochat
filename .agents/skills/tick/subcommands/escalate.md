# /tick escalate

The six-way menu for terminal escalations: `/tick escalate <id> [reason]`.

Fired automatically by `review` on MANUAL (including critical/high
security findings) and on round-cap; also available manually. Reason values
(recorded in `escalation_reason:`): `round-cap | manual-verdict |
critical-high-finding | premise-fail | loop | spec-gap`.

Print the menu in chat and STOP for the user's typed choice:

```
<id>: <title> — ESCALATED
Trigger: <reason>

Reviewer's last verdict: <verbatim MANUAL block / finding summary>

Choose:
  1. refine     — the ticket's scope or acceptance was wrong; amend it, then continue
  2. override   — the gate was too strict; record the override and approve
  3. decompose  — split into N tickets via /tick analyze <hurdle brief>
  4. defer      — block on a new ticket the work surfaced; pause this one
  5. spec-amend — the spec itself is wrong; raise an amendment, pause
  6. abandon    — decided against; terminal

Reply with: <number> [optional notes]
```

Per-arm:

- **refine** → apply the user's stated amendment to the ticket (scope,
  acceptance, approach), clear `escalation_reason:`, status →
  `in-progress` (branch exists) or `pending` (no branch). The commit
  message records the refine reason; git log is the audit trail.
- **override** → record the gate's specific objections under `overrides:`
  with the user's one-line justification; status → `in-review`; proceed to
  commit. `OVERRIDE-APPROVE` is recorded in `reviews:` (never used by the
  gate itself — only this subcommand writes it).
- **decompose** → route through `/tick analyze <the hurdle/brief>`: the
  analyst produces the children (never title-only skeletons). Operand →
  `deferred` (retains residual work; `deferred_reason: decomposed`) or
  `abandoned` (`abandoned_reason: decomposed`) per whether children fully
  replace it.
- **defer** → user names the blocker; operand → `deferred` with
  `deferred_on:` and `deferred_reason: blocked-on-new-ticket`; the blocker
  becomes a `/tick analyze` brief.
- **spec-amend** → the amendment is a `spec:` commit (or a
  `spec_amend_for` ticket); operand → `deferred` (`spec-amend`) if it will
  reopen, else `abandoned` (`obsoleted-by-spec-amend`).
- **abandon** → `abandoned` with `abandoned_reason: superseded` (name the
  absorbing ticket) or `wont-do-infeasible`. Terminal.

Regenerate `STATUS-TICK.md` after every arm.
