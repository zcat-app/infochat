---
name: persist-reached-decisions
description: "A design decision reached in a session is a deliverable — commit it or write it to a memory store before the session ends; conversation context is volatile."
metadata:
  type: feedback
---

When a session produces a design or process decision (a finalized skill spec,
an agreed approach, a set of answers to clarifying questions), persist it
before the turn ends — commit it to the repo, or write it to the shared memory
store — rather than leaving it only in conversation context.

**Why:** a whole subcommand design was decided in one session via a series of
clarifying questions and never written to disk; the context was later cleared
and the work was lost, and the user had to discover the loss and ask for it to
be redone. That wastes their time and erodes trust. Any agent's context is
volatile in the same way — this is not a property of one harness.

**How to apply:** treat a reached decision as a deliverable with a home. If it
is a repo artifact (a skill, a process rule, a ticket, a spec line), write and
commit it that same turn. If it is durable knowledge rather than an artifact,
write it to `.agents/memory/` (portable) or `.agents/memory-local/`
(machine-specific). If your harness keeps its own session transcripts, they are
a recovery path worth checking before asking the user to re-explain something —
but recovering from a transcript is the fallback, not the plan.
