---
id: M1-415
title: "test: golden-path end-to-end journey IT"
status: done
created: 2026-06-20
last_updated: 2026-06-21
blocked_by: []
clarity_check:
  date: 2026-06-21
  verdict: WARN
  warnings:
    - 'Acceptance item 2 ("asserts the observable outcome at each step") is a reviewer-inspected quality gate, not a machine-checkable criterion; as written it weakens the acceptance contract but is not a blocker.'
  blockers: []
reviews:
  - round: 1
    date: 2026-06-21
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 691
      removed: 8
files_budget: 3
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/journey
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The existing slice ITs (InviteIntakeRoundtripIT, GroupLifecycleIT, AssetCommandsRoundtripIT, DigestRoundtripIT, AdminBootstrapIT) — unchanged; this ticket ADDS one end-to-end narrative test, it does not modify or replace the slice tests.
  - Production adapters — the journey runs entirely on the in-memory adapter and TestLlmProvider.
  - src/main code — this is a test-only addition; if the journey reveals a product bug, file a follow-up ticket rather than fixing inline.
acceptance:
  - "A single integration test under app.zcat.infochat.provider.journey (e.g. GoldenPathJourneyIT) drives one continuous narrative on the in-memory adapter with TestLlmProvider: bootstrap admin present -> /invite create -> register via the code (probation begins) -> a probation-blocked command is rejected with the probation reply -> graduation (/vouch) -> a DM content command succeeds -> a chat-mode turn returns the stubbed reply -> a group @mention is held pending -> /approve-group -> a group command succeeds -> a digest is produced -> an asset command replies -> /ban -> the banned user receives the fixed reply and reaches no further processing."
  - "The journey asserts the observable outcome at each step (reply text or DB state), not merely that no exception was thrown."
  - "Content commands in the journey return seeded posts (the test seeds READY content inline or reuses the M1-413 fixture if present)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/journey (GoldenPathJourneyIT)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/verification.md §Spec-level invariants the tests must enforce
  - docs/spec/security.md §Authorization model
decision_refs:
  - D44
  - D45
  - D47
---

# M1-415: golden-path end-to-end journey IT

## Context

Coverage today is strong but split across many slice ITs; no single test walks
the whole user journey as one continuous narrative. This ticket adds that
golden-path test — the durable regression artifact that proves the deliverables'
phases (setup → admin config → usage) hang together end to end. Origin:
`docs/testing/USER_TEST_PLAN.md` deliverable #4.

## Acceptance

See frontmatter. One IT chains bootstrap → invite → register → probation →
graduate → DM command → chat → group pending → approve → group command → digest
→ asset → ban, asserting the observable outcome at each hop. Full `mvn verify`
green.

## Out-of-scope

See frontmatter. The existing slice ITs are untouched; this is an additive
test-only narrative. A product bug surfaced by the journey is a follow-up
ticket, not an inline fix (engineering-rules §Surgical changes).

## Notes

- This is deliberately a breadth test, not a depth test: it proves the steps
  connect, while the slice ITs keep proving each step's edge cases. Keep
  assertions to one clear observable per hop to stay readable.
- Reuse M1-413's seed fixture for `/summary` content if it has landed; otherwise
  insert a minimal READY post inline so the ticket is not hard-blocked on M1-413.
- Adjacent pattern: `InviteIntakeRoundtripIT` (the 7-step invite/ban narrative)
  is the closest existing shape; this extends that style across the full journey.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-415-*.md
```
