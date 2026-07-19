---
name: id-claims-invisible-to-registries
description: "Ticket IDs and D-numbers get claimed in pending/untracked artifacts that the registry everyone consults cannot see — check the claimants, not just the registry, before allocating."
metadata: 
  type: feedback
  modified: 2026-07-18T18:15:03.405Z
---

Both identifier spaces in this repo are allocated by reading a registry that
does **not** hold the live claims. Hit twice on 2026-07-18, independently.

- **D-numbers.** `docs/spec/decisions.md` lists only *landed* rows. A pending
  ticket claims its future number in **acceptance prose** (there is no field
  for "decision this ticket creates"; `decision_refs` means rows it *cites*
  and is **validated by nothing** — not `lint-ticket.py`, and not the clarity
  gate, whose SPEC-REFS-VALID check covers `spec_refs` headings only).
  Result: M1-653 shipped **D64** after verifying "D62 is max in the file",
  while M1-648 had held D64 in its acceptance item 8 since `db200608` and
  M1-649 cited it. Two claimants; the landed row wins (append-only forbids
  renumbering), the unlanded claims must move.
- **Ticket IDs.** An *untracked* draft in a concurrent session's checkout
  claims an ID that `ls docs/plan/m1/tickets/` on committed state cannot see
  (M1-654 → forced the flake fix to M1-655, then the draft was rewritten as
  M1-656 leaving 654 a permanent hole).

**Why:** the registry is a lagging index of *completed* allocations, and
allocation happens in artifacts that are pending, uncommitted, or on another
branch. Verifying against the registry feels like verification and is not.

**How to apply:** before claiming either kind of identifier, grep the
*claimants*, not the index:
- D-number: `grep -rn 'D6[0-9]' docs/plan/m1/tickets/*.md` — read the
  **acceptance prose**, not just `decision_refs`.
- Ticket ID: `ls docs/plan/m1/tickets/` **and**
  `git status --short docs/plan/m1/tickets/` in the primary checkout, **and**
  `git worktree list` (per-branch status is invisible from `main` — see
  [[m1-tick-start-precondition-blind-to-worktrees]]).

Generalises past IDs: **any time a doc tells you a slot is free, ask what
would hold a claim that this doc cannot see.** Related:
[[verify-subagent-quotes-before-pinning]] — the clarity subagent asserted
D64's row "explicitly names M1-642 as the reason D63 was reserved"; D63
appears nowhere in `decisions.md`. Verify the citation, then verify the
claimants.
