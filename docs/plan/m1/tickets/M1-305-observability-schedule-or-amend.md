---
id: M1-305
title: "Observability commitments: schedule implementation or amend as deferred (decision)"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 5
files_scope:
  - docs/spec/llm.md
  - docs/design/05-llm-and-embeddings.md
  - docs/design/06-messaging.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Implementing any metrics surface in this ticket — if the user picks "schedule", the output is follow-up tickets, not code here.
  - Logging (exists) — only the committed-but-absent metrics/trace-context surfaces.
acceptance:
  - "The spec/design observability commitments that are entirely unimplemented — docs/spec/llm.md's per-call context (trace/scope id) and latency/token metrics, and design 06-messaging.md §6.12's AdapterMetrics (including the §6.3.8 adapter.outbound.update.total{outcome=fallback_send} counter M1-285 explicitly defers) — are resolved one of two ways: (a) amended in place as deferred-to-v2 with a short rationale, or (b) covered by newly filed implementation tickets referenced from the docs; one of the two, applied consistently to ALL the listed surfaces (grep 'AdapterMetrics' across infochat-* returns zero main-source hits today, verified 2026-06-11)."
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

# M1-305: Observability commitments: schedule implementation or amend as deferred (decision)

## Context

Deep-review v5 carried **U-69** (CT5 cross-cut)
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; sources fable-5/04+05
observations — gitignored; all load-bearing facts inlined):

The spec and design commit to an observability surface nobody built: LLM
per-call trace/scope context and latency/token metrics, and the adapter
metrics catalogue of design §6.12. M1-285 and M1-302 both bump into the gap
(fallback counter, releasedStage2FailedCount visibility). The report asks
for an explicit schedule-or-amend decision so the docs stop promising what
the code doesn't do.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **⚠ User decision at start.** Default if the user just says "go": (a)
  amend as deferred-to-v2 — a metrics dependency (Micrometer) would itself
  need the recorded dependency-approval flow, which deserves its own
  proposal, not a rider.
- Keep amendments surgical: mark the sections deferred; do not delete the
  catalogue text (it is the v2 spec).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-305-*.md
```
