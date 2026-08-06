# /tick hurdle

The implementor's stop-and-report, for a divergence the ticket's contract
cannot absorb. A hurdle is one of exactly four (workflow §Principles 4):

1. the reproduction proves the ticket's premise wrong (including a
   premise-fail surfacing in `mvn verify`);
2. the fix needs another Maven module, or a file another in-flight ticket
   holds;
3. the fix needs a spec change;
4. the change would drop a control the replaced path carried
   (engineering-rules §10) and the ticket does not authorize that.

Anything else is execution, not a hurdle: an Approach step that does not
hold in the code, a better helper than the one named, a rewrite instead of
a patch, an extra test file. Proceed, and let the merged review gate judge
the result against the contract. Silent drift is still a failure mode — the
gate is where it is caught, not a stop at every surprise.

Invocation: `/tick hurdle <id>` from the in-progress ticket's branch.

## Steps

1. **Write the hurdle report** in chat, exactly this shape:

```
HURDLE REPORT — <id>: <title>

PLANNED: <what the ticket's Approach said would happen — the step, the
claim, the file>

FOUND: <what the code actually shows — the discrepancy>

ROOT CAUSE: <why the plan was wrong or the code differs; evidence with
file:line or the grep that proves it>

SUGGESTED SOLUTIONS:
  (each solution names what was tried and rejected — the falsification
  note that says why the alternative lost. A solution is only as good
  as the option it defeated; confirming your first idea is not
  verification.)
  1. <solution> — <cost / risk / what changes> — rejected: <alternative
     tried and why it failed>
  2. <alternative> — <cost / risk / what changes> — rejected: <why this
     one loses to 1>

OPTIONS:
  a) refine    — amend this ticket (approve the change, adjust the
                 Approach/acceptance via escalate->refine), then continue
  b) new-ticket — the hurdle is a separate problem; file it via
                 /tick analyze <hurdle brief>; this ticket continues as
                 planned or pauses on it
  c) spec-amend — the spec is the obstacle; raise a spec: amendment first
  d) drop       — the plan premise is dead; abandon this ticket
RECOMMENDED: <a|b|c|d> — <one-line why>
PLAIN-ENGLISH SUMMARY: <the problem in one sentence, the recommended
action in one sentence — no jargon, no check names>
```

2. **Present and stop.** Do not continue implementing until the user picks
   an option. The report is printed in full — no burying the recommendation
   among tool output.

3. **Apply the decision.**
   - `refine` → run `/tick escalate <id> refine` with the user's stated
     change, then continue implementing the amended plan.
   - `new-ticket` → the user drives `/tick analyze <hurdle brief>` (the
     hurdle report IS a valid brief). This ticket continues per the
     decision (usually as planned; sometimes with `deferred_on` set).
   - `spec-amend` → the amendment becomes a `spec:` commit or a
     `spec_amend_for` ticket; this ticket waits (`deferred`,
     `deferred_reason: spec-amend`).
   - `drop` → `/tick escalate <id> abandon` with `abandoned_reason:
     wont-do-infeasible` (or superseded if another ticket absorbs it).

## What a hurdle is NOT

A hurdle is not a style preference, an alternative that merely *also*
works (§3 of the engineering rules: complete the ticket as written,
record the alternative in the commit message), or a rename suggestion
(those go in the `Renames:` trailer). When unsure whether something is a
hurdle, ask — one blocking question — rather than deciding silently in
either direction.

## The falsification note (binding)

Each suggested solution carries what was tried and rejected. "Rejected"
means you actually checked the alternative against the code and it lost
on evidence — a cheaper scope, a simpler shape, the sibling pattern the
ticket's own analysis declined. A note like "rejected: widening the
notice window — the limit is a cost control (spec §X)" is the note;
"rejected: considered option B" is decoration. This is the house rule
(verify-before-recommending) made structural: the recommendation is only
as trustworthy as the option it defeated.
