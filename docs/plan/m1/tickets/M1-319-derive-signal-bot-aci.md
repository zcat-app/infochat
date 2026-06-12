---
id: M1-319
title: "Derive Signal bot ACI from signal-cli at startup"
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

# M1-319: Derive Signal bot ACI from signal-cli at startup

## Context

Decomposed from M1-318 (budget-breach). The Signal half of the
bot-contact-id derivation: replace the operator-typed
`infochat.adapters.signal.bot-aci` property with a value derived from
the signal-cli account the adapter already runs. M1-318's feasibility
investigation (2026-06-12) confirmed: `listAccounts` does NOT expose
the ACI (number only); the viable mechanisms are a `getUserStatus`
self-query over the already-held JSON-RPC connection (JSON output
carries `uuid`) or reading the ACI from signal-cli's account file in
the configured data-dir (the design's §6.5.5 "identity store" model).

## Acceptance

SKELETON — to be filled in before `/m1-tick start M1-319` (clarity
pre-flight FAILs until acceptance, sizing, and out_of_scope are set).

## Out-of-scope

SKELETON — to be filled in.

## Notes

- Parent investigation record: M1-318 frontmatter `escalations:` entry
  (2026-06-12) and `target/m1-tick-outline-M1-318.md` (worktree-local).
- Decoupling invariant from the parent carries over: derive from the
  bot's OWN account, never from `infochat.adapters.signal.admin`;
  admin-key rotation must not move the D10 anchor.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-319-*.md
```
