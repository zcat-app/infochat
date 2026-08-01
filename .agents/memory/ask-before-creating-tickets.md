---
name: ask-before-creating-tickets
description: Always ask before creating a ticket file — even when the discussion reads like approval; drafting is a decision the user owns.
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

Same family as [[ask-dont-assume-ambiguous-or-irreversible]] (ask when a
stop/kill word arrives mid-background-work) — the general shape: when an
action starts a new workflow entity or is hard to walk back silently, the
agent asks; the user decides.

(Origin: 2026-08-01, user feedback — "you ASSUMED you should create ticket
without asking! THAT STRICTLY IS FORBIDDEN! YOU HAVE TO ASK EVERY TIME".)
