---
id: M1-322
title: "Adapter observability: AdapterMetrics catalogue"
status: pending
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: [M1-321]
files_budget: 14
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "/status adapter reporting (design 06 §6.12 last line: adapter name, trust level, connection status, queue sizes) — a thin follow-up once the metrics exist; file it when this ticket lands."
  - LLM/embedding metrics and the call context (M1-321).
  - New verification-harness surfaces beyond the named tests (design 06 §6.14 already enumerates the adapter contract suite; this ticket does not extend it).
  - Dashboards, exporter endpoints, or Prometheus configuration.
acceptance:
  - "AdapterMetrics emits the design 06 §6.12 catalogue via Micrometer: adapter.inbound.total{adapter, scope_kind}, adapter.outbound.total{adapter, scope_kind, outcome}, adapter.inbound.queue.size{adapter}, adapter.outbound.queue.size{adapter}, adapter.connection.status{adapter}, adapter.identity.assert.fail{adapter}, adapter.simplex.auth.fail{adapter}, adapter.message.bytes{adapter, direction}, adapter.outbound.update.total{adapter, scope_kind, outcome}, adapter.outbound.update.fail{adapter, reason}, adapter.outbound.update.lag{adapter}, adapter.typing.toggle{adapter, scope_kind, value}, with the §6.12 label/outcome domains; named tests assert increments for at least inbound.total, outbound.total per outcome, and connection.status transitions."
  - "The §6.3.8/§6.4.5 edit-failure fallback path increments adapter.outbound.update.total{outcome=fallback_send} and adapter.outbound.update.fail{reason=…} — the counter M1-285 explicitly deferred; a named test drives a simulated CEInvalidChatItemUpdate through the fallback and asserts both increments."
  - "The invite_drop_total counter (docs/design/04-security.md; deferred by M1-044a with a TODO in InviteCodeConsumer) is registered and increments on the documented drop path; the TODO comment is resolved by the implementation; a named test asserts the increment."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds: []
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

# M1-322: Adapter observability: AdapterMetrics catalogue

## Context

Filed by M1-305 (deep-review v5 finding U-69, user decision 2026-06-12:
schedule, not defer). Design 06-messaging.md §6.12 pins a twelve-metric
`AdapterMetrics` Micrometer catalogue; §6.3.8/§6.4.5 additionally
record the edit-failure fallback in
`adapter.outbound.update.total{outcome=fallback_send}` — a counter
M1-285 implemented the behavior for but explicitly deferred the metric
of. As of 2026-06-12 `grep AdapterMetrics` across `infochat-*` sources
returns zero hits; the only Micrometer traces in the tree are two
javadoc/TODO notes pointing at exactly this gap
(`AdapterReadinessCheck`, `InviteCodeConsumer`).

## Acceptance

See frontmatter. In prose: (1) `AdapterMetrics` emits the full §6.12
catalogue with the documented labels and value domains; (2) the
edit-fallback path increments the `fallback_send` outcome and the
per-reason fail counter, test-driven through a simulated
`CEInvalidChatItemUpdate`; (3) the M1-044a-deferred
`invite_drop_total` counter is registered and tested, retiring the
`InviteCodeConsumer` TODO; (4) the full suite stays green.

## Out-of-scope

See frontmatter. The `/status` adapter-reporting line stays out for
the same reason as M1-321's aggregate view: this ticket's surface is
"emit the committed telemetry", not the read-side command. The
existing `AdapterContractTest` suite (§6.14) is not extended — new
assertions live in this ticket's own named tests.

## Notes

- Blocked by M1-321, which carries the Micrometer dependency through
  the recorded dependency-approval flow. This ticket adds no new
  dependency; if M1-321's approved scope did not reach
  `infochat-messaging-adapter`/`infochat-provider` poms, extending it
  there is in-budget here.
- Emission points: inbound dispatch and outbound send/update/typing
  paths in `infochat-messaging-adapter` (SimpleX + Signal + InMemory),
  connection lifecycle (§6.4.6 — `adapter.connection.status`,
  `adapter.simplex.auth.fail` with its 3-consecutive
  AUTH_FAILED terminal interplay), and
  `infochat-provider/.../messaging/InviteCodeConsumer` for
  `invite_drop_total`.
- `adapter.outbound.update.lag` is a histogram of caller-`update()`
  to transmitted-edit time after coalescing — the coalescer owns the
  timestamps.
- Design catalogue: `docs/design/06-messaging.md` §6.12 (annotated as
  scheduled-by-this-ticket by M1-305).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-322-*.md
```
