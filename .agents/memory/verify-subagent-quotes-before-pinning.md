---
name: verify-subagent-quotes-before-pinning
description: "Read the exact source line before pinning it in a ticket acceptance item — Explore-agent \"quotes\" can be paraphrases that invent details."
metadata: 
  type: feedback
---

While drafting M1-562, an Explore subagent "quoted" a SignalMessageCodec
comment as having inverted legacy/current labels; the real comment had no
such labels. The paraphrase went verbatim into an acceptance item, which
became unsatisfiable and cost a premise-fail escalation mid-implementation.

**Why:** survey subagents summarize; their "exact lines" are sometimes
reconstructions. A ticket acceptance item is a 100% claim about the code
(CLAUDE.md §Verify before recommending applies with full force to
drafting).

**How to apply:** before an acceptance item asserts anything about a
specific line/comment/signature, Read that line directly in the main
session — subagent output is a pointer, not evidence. Same for scope
claims: grep constructor/call sites before declaring a file "unmodified"
in out_of_scope (the M1-562 budget-breach escalation was the same root
cause: fixtures checked, call sites not).
