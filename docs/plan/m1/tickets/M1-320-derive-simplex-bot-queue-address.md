---
id: M1-320
title: "Derive SimpleX bot queue address via APIShowMyAddress"
status: pending
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: []
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-318
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-320: Derive SimpleX bot queue address via APIShowMyAddress

## Context

Decomposed from M1-318 (budget-breach). The SimpleX half of the
bot-contact-id derivation: replace the operator-typed
`infochat.adapters.simplex.bot-queue-address` property with a value
derived by querying simplex-chat over the WebSocket connection the
adapter already holds. M1-318's feasibility investigation (2026-06-12)
confirmed the official client API (stable branch) exposes
`APIShowMyAddress` returning a `userContactLink`; the adapter extracts
the bare queue id from it (the same value the operator copies manually
today).

## Acceptance

SKELETON — to be filled in before `/m1-tick start M1-320` (clarity
pre-flight FAILs until acceptance, sizing, and out_of_scope are set).

## Out-of-scope

SKELETON — to be filled in.

## Notes

- Parent investigation record: M1-318 frontmatter `escalations:` entry
  (2026-06-12) and `target/m1-tick-outline-M1-318.md` (worktree-local).
- Decoupling invariant from the parent carries over: derive from the
  bot's OWN address, never from `infochat.adapters.simplex.admin`;
  admin-key rotation must not move the D10 anchor.
- The codec/client model no self-address command today
  (`SimpleXMessageCodec` / `SimpleXWebSocketClient`) — the request
  encoder and response decode are new surface this ticket adds.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-320-*.md
```
