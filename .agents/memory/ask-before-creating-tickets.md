---
name: ask-before-creating-tickets
description: Always ask before creating a ticket file — even when the discussion reads like approval, and even when an in-flight ticket's acceptance mandates a follow-up; drafting is a decision the user owns.
metadata:
  type: feedback
---

Creating a new ticket file under `docs/plan/m1/tickets/` (or any milestone)
requires an explicit user confirmation **every time**, however obvious the
reading feels. A preceding discussion that points at "we need a ticket for
this" — even the user's own words in an option menu's free-text reply — is
NOT the confirmation; the ask is a separate, explicit step before the file
is written (and again before the `M1-NNN: draft ticket` commit, per the
standing git-mutation rule).

Follow-up tickets are the sharpest case. An in-flight ticket's own
`acceptance:` item may mandate one ("a follow-up implementation ticket
exists and is referenced by id"), and the user picking the decision that
triggers it (e.g. "populate it") STILL confers no permission to write the
file. The acceptance item creates the need; the decision pick authorizes
the decision record; the ticket file gets its own explicit ask, every
time. Announcing "next free ID is M1-NNN" is fine — writing the file is
the gated step.

Same family as [[ask-dont-assume-ambiguous-or-irreversible]] (ask when a
stop/kill word arrives mid-background-work) — the general shape: when an
action starts a new workflow entity or is hard to walk back silently, the
agent asks; the user decides.

(Origin: 2026-08-01, user feedback — "you ASSUMED you should create ticket
without asking! THAT STRICTLY IS FORBIDDEN! YOU HAVE TO ASK EVERY TIME".
Reinforced same day: an agent treated M1-715's acceptance item mandating a
follow-up ticket plus the user's decision pick as authorization to write
follow-up M1-743 — "you should always ask if you are allowed to create
follow up ticket if i did not explicitly tell you!")
