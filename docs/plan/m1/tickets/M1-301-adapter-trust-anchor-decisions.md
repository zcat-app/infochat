---
id: M1-301
title: "Adapter trust-anchor reconciliation: Gate 4 scope, bot-id provenance (decisions)"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 8
files_scope:
  - docs/spec/messaging.md
  - docs/spec/deployment.md
  - docs/spec/security.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Gate 4's group-SPI-wired truth source (resolved by M1-280) — only the spec-vs-gate SCOPE disagreement is in play.
  - The mention-recognition comparison logic itself (D10) — only where its trust anchor VALUE comes from.
  - Bot-id well-formedness validation (M1-294 U-35).
acceptance:
  - "U-33 resolved to one truth: docs/spec/messaging.md ~:64-66 says a supportsMentionByContactId=false adapter 'refuses to enable group mode', but AdapterRegistry Gate 4 (:179-194) rejects the adapter entirely at startup; either the gate is scoped to group-mode refusal (code change + named test: such an adapter starts, serves DMs, refuses group traffic) or the spec is amended to v1-unconditional rejection adopting the gate's own in-code rationale (v1 has no per-deployment groups-off toggle, so refusal == startup failure); one of the two, recorded explicitly."
  - "U-34 resolved to one truth: docs/spec/deployment.md ~:139 commits, verbatim, that the per-adapter bot contact id 'is derived from this identity material at adapter startup; it is not an operator-typed property' — but infochat.adapters.simplex.bot-queue-address / infochat.adapters.signal.bot-aci are @ConfigProperty-driven and feed the D10 mention-recognition trust anchor (the in-code comment acknowledges the dropped derivation plan); either derivation from adapter identity material is implemented per adapter (named tests), or the spec sentence is amended AND docs/spec/security.md §Per-adapter admin threat profile gains a sentence covering the operator-typed-anchor risk (a mistyped/poisoned bot id misdirects mention recognition); one of the two, recorded explicitly."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-301: Adapter trust-anchor reconciliation: Gate 4 scope, bot-id provenance (decisions)

## Context

Deep-review v5 verified **U-33** (MEDIUM) and **U-34** (HIGH per opus-47)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3; sources `opus-47/01#F3` and
`opus-47/01#F1`, both unique finds — gitignored; all load-bearing facts
inlined; spec sentences re-verified verbatim 2026-06-11).

Both are spec-vs-code disagreements on the adapter trust surface, where
the right fix may be the spec: the report explicitly frames them as
decisions, not defects.

## Acceptance

See frontmatter — each item is an explicit fork; the diff (code or spec)
must pick a side and record why.

## Out-of-scope

See frontmatter.

## Notes

- **⚠ Both forks are user decisions at start.** Defaults if the user just
  says "go": U-33 → spec amendment (the gate's in-code rationale is
  correct for v1's no-groups-off-toggle reality and is essentially the
  amendment text); U-34 → spec amendment + threat-model sentence
  (derivation is real feature work: SimpleX would query the CLI for its own
  queue address, Signal would read the account ACI from signal-cli — worth
  doing, but as its own scheduled ticket, not a rider).
- If the user instead picks implement-derivation for U-34, refine this
  ticket: the adapter files move from comment-touch to feature scope and
  the budget needs revisiting.
- security_relevant: true because both items sit on the D10
  mention-recognition trust anchor; the spec sentences quoted in
  acceptance are transcribed per the recorded security-ticket rule.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-301-*.md
```
