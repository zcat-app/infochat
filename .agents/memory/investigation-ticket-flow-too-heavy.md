---
name: investigation-ticket-flow-too-heavy
description: "For investigation-only M1 tickets, reconsider the full m1-tick cycle — user feedback 2026-07-15"
metadata: 
  type: feedback
---

An M1 ticket whose ONLY deliverable is a finding (+ maybe filing a follow-up
ticket) — no product code — should not be forced through the full
implement → review → redteam → merge cycle. User raised this on M1-629
(2026-07-15): it was framed "investigate and decide/implement", the investigation
concluded "guard is correct but structurally unreachable; the fix is a bigger,
higher-risk change", and the only sane next step was to file a properly-sized
implementation ticket (M1-634) and close M1-629. Running the heavy cycle to reach
"file another ticket" felt like overhead. The user also noted they'd read a
combined "investigate AND apply" ticket as legitimately doing both — the mismatch
was that M1-629's apply-half turned out to exceed its own sizing/gates.

**Why:** the m1-tick gates (clarity → plan-writer → mvn verify → reviewer →
redteam → merge) exist to protect *code changes*. An investigation ticket
produces a document/decision, which those gates don't meaningfully protect; the
process cost buys little.

**How to apply:**
- When a ticket is investigation-only (its acceptance is "determine/decide with
  evidence", no product diff expected), flag that up front and prefer a lighter
  path — e.g. do the investigation, record the finding, and if it spawns real
  work, file a fresh correctly-gated ticket. Don't ceremonially run the full
  cycle just to emit another ticket.
- If an "investigate AND apply" ticket's apply-half turns out larger/riskier than
  the ticket was sized/gated for (higher complexity, security-relevant, over
  files_budget), that is a legitimate decompose → close-investigation +
  file-implementation (what M1-629 → M1-634 did). See also [[ask-dont-assume-ambiguous-or-irreversible]].
- Decompose-into-ONE is a valid move here: its value is the fresh `start` gate
  (clarity + plan-writer) and correct-from-scratch sizing on a new ID, which
  refine-after-budget-breach cannot deliver (refine stays in-progress on the old
  branch, skips clarity + plan-writer).
