---
id: M1-318
title: "Derive per-adapter bot contact id from adapter identity material (SimpleX queue address, Signal ACI)"
status: abandoned
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: []
files_budget: 10
files_scope:
  - docs/spec/deployment.md
  - docs/spec/security.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProductionAdapterBeans.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-318.md
abandoned_reason: decomposed
escalations:
  - date: 2026-06-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalated by developer before implementation. Feasibility-gate
      investigation confirmed derivation is feasible for both adapters
      (SimpleX: APIShowMyAddress → userContactLink; Signal: getUserStatus
      self-query carries uuid/ACI, or identity-store read — listAccounts
      does NOT carry the ACI, contra acceptance item 2's hint), but the
      honest both-adapter accounting (codec/client/adapter code + new
      tests + provider decoupling test + spec edits + 06-messaging.md
      design alignment) exceeds files_budget: 10, and the decoupling test
      and design file lie outside files_scope. Decomposed into M1-319
      (Signal) and M1-320 (SimpleX).
out_of_scope:
  - Bot-id well-formedness validation (M1-294 U-35) — only the SOURCE of the anchor value changes here, not its validation.
  - The D10 mention-comparison logic itself — only where the anchor VALUE originates.
  - Admin (bootstrap) contact id provenance — that stays an operator property per deployment.md §Operator inputs item 2; this ticket touches only the BOT identity anchor.
acceptance:
  - "SimpleX derives its bot queue address from the adapter's own identity material at startup (e.g. querying simplex-chat for the bot's own address) rather than the infochat.adapters.simplex.bot-queue-address operator property; the derived value feeds the D10 trust anchor and is validated non-blank; named test pins derivation."
  - "Signal derives its bot ACI from the signal-cli account at startup (e.g. listAccounts / whoami on the configured account) rather than the infochat.adapters.signal.bot-aci operator property; the derived value feeds the D10 trust anchor and is canonicalized + validated non-blank; named test pins derivation."
  - "The derived anchor stays decoupled from the bootstrap-admin contact id: admin-key rotation MUST NOT move the bot's D10 anchor (preserve the ProductionAdapterBeans rationale that motivated the distinct config key)."
  - "docs/spec/deployment.md §Operator inputs item 7 reverts to derivation-from-identity-material wording (the bot contact id is not an operator-typed property); docs/spec/security.md §Per-adapter admin threat profile 'Operator-typed bot-identity anchor' note is removed or updated to reflect the now-closed risk."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
decision_refs: []
spec_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-12
  verdict: WARN
  warnings:
    - "Acceptance item 3 does not name a test that pins the decoupling invariant (admin-key rotation leaves the D10 anchor unchanged)."
    - "Acceptance item 4 does not name the specific post-amendment wording that must appear in deployment.md §Operator inputs item 7."
  blockers: []
---

# M1-318: Derive per-adapter bot contact id from adapter identity material (SimpleX queue address, Signal ACI)

## Context

Follow-up to M1-301 (U-34). M1-301 resolved the spec-vs-code disagreement
on the bot-identity trust anchor by amending the spec to admit the
operator-typed property the code already used, and recording the residual
mistype/poison risk in `docs/spec/security.md` §Per-adapter admin threat
profile ("Operator-typed bot-identity anchor"). The deep-review framed real
derivation as worth doing but as its own scheduled ticket, not a rider on
the decision ticket — this is that ticket.

The D10 mention-recognition trust anchor (`messaging.md` §Required SPI
surface) compares a group message's mention payload byte-for-byte against
the bot's per-adapter contact id. Today that id is an operator-configured
property (`infochat.adapters.simplex.bot-queue-address`,
`infochat.adapters.signal.bot-aci`), validated only as non-blank. A
mistyped or substituted value silently misdirects mention recognition.
Deriving the anchor from the adapter's own identity material removes the
typo/substitution surface.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Feasibility gate at start.** The in-code comment at
  `ProductionAdapterBeans` (the SimpleX identity block) records that a
  dataDir-to-identity parse "was once planned and dropped" — so a
  `complexity: high` plan-writer outline at `/m1-tick start` must first
  confirm simplex-chat and signal-cli actually expose the bot's own
  address / ACI through a stable query before committing the adapter-code
  scope. If neither tool exposes it cleanly, escalate (the decision may be
  to keep the operator property and close this ticket as spec-confirmed).
- **Decoupling invariant.** Whatever the derivation source, it must remain
  the BOT's identity material, never the bootstrap admin's — the distinct
  config key existed precisely so admin-key rotation could not silently
  move the D10 anchor. Derivation must preserve that separation.
- security_relevant: true — this sits directly on the D10 trust anchor.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-318-*.md
```
