---
id: M1-447
title: "Make decision-logic time injectable (sweep now() sites)"
status: pending
created: 2026-06-24
last_updated: 2026-06-24
blocked_by: []
files_budget: 16
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "The coding-style RULE text (docs/process/engineering-rules-verbatim.md, CLAUDE.md §Coding style) and the docs/process/reviewer-prompt.md check that enforces it. Those companion process changes (items 3–4 of the 2026-06-24 design discussion) land as separate `process:` commits — they are pure-doc and bypass the ticket flow — not in this code ticket. This ticket should be started AFTER the rule lands so the implementer converts against an agreed standard."
  - "(B) pure audit/record writes and Flyway DDL `DEFAULT now()` — left on the DB clock (the system-of-record convention). Converting them would diverge timestamp authorship from the rest of the schema for no testability gain. See acceptance item 3."
  - "ReEvaluationJob — already converted by M1-444 (the reference implementation). Do not re-touch it."
  - "Any behavioural change to a time window / threshold / cadence. This is a determinism refactor: with the real production Clock, behaviour is byte-for-byte preserved. Changing a window's size is a separate ticket."
acceptance:
  - "Every production `now()` / `Instant.now()` site (Java SQL-string literals, `Instant.now()`, and Flyway `DEFAULT now()`) is classified in the diff (a brief `docs/plan/m1/now-clock-audit.md` or equivalent) into: (A) decision-logic time source — a comparison/gate that determines behaviour (scan windows, cooldowns, TTL/expiry checks, rate-limit windows, probation/ban/invite-expiry timing) — vs (B) pure audit/record write — created_at/updated_at/status_changed_at stamps and DDL defaults never read back to gate a decision. The classification is explicit and reviewable, and is what scopes the conversion."
  - "Every (A) decision-logic site reads its current instant from an injected `java.time.Clock` (the app-wide `@Produces @ApplicationScoped Clock` in `ThrottledAdminNotifier.systemUtcClock()`; test seam `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`), never inline `now()` / `Instant.now()`. Per the M1-444 rule, a component that reads back its own time-write for a decision moves wholesale to the one clock — no two-clock split (a value written by one clock and compared against another)."
  - "(B) pure audit/record writes and DDL `DEFAULT now()` are LEFT on the DB clock and explicitly NOT converted."
  - "Each converted component gains or extends a test that pins the Clock to a fixed instant and asserts the time-gated behaviour deterministically (named per component in the diff), so the suite no longer depends on the wall-clock date — the time-bomb class M1-398 / M1-400 / M1-444 each fixed one instance of."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "per-component fixed-Clock tests (named at implementation time, one per converted (A) component)"
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

# M1-447: Make decision-logic time injectable (sweep now() sites)

## Context

M1-444 fixed `ReEvaluationJob`: its candidate-scan window read the current
instant from SQL `now()` inside decision logic, so the test could not pin time
without a wall-clock-relative hack, and the fixture aged out and went red on a
date boundary. That is the **third** instance of the same time-bomb class
(M1-398 `EmbeddingWorkerIT`, M1-400 `EntityExtractorWorkerIT`, M1-444
`ReEvaluationJobScheduledPathIT`), each fixed one-off. The 2026-06-24 design
discussion concluded the root cause is general: **time used in decision logic
must be an injectable parameter**, not ambient `now()` / `Instant.now()`, so it
can be pinned in tests and is never split across two clocks (app vs DB).

This ticket sweeps the remaining production sites onto the injectable-`Clock`
pattern M1-444 established. The surface is large (~100 `now()` / `Instant.now()`
matches across both services) but the *convertible* set is much smaller: most
matches are pure audit-timestamp writes and DDL defaults that legitimately stay
on the DB clock. The work is therefore **classify first, then convert only the
decision-logic (A) sites** — which is why this is `complexity: high` and will be
scoped (and likely decomposed per-component) by the plan-writer at `start`.

## Acceptance

See the YAML `acceptance:` list. In short: classify every production `now()` /
`Instant.now()` into (A) decision-logic vs (B) pure-audit; convert the (A) sites
to an injected `Clock` (whole-component, no two-clock split); leave (B) on the
DB clock; add a fixed-Clock test per converted component; full suite green.

## Out-of-scope

See the YAML `out_of_scope:` list. The coding-style **rule** and the
**reviewer-prompt** check (items 3–4 of the plan) are pure-doc `process:`
commits done separately and SHOULD land first; this ticket is the code sweep.
(B) audit writes and DDL `DEFAULT now()` stay on the DB clock. `ReEvaluationJob`
is already done. No time-window behaviour changes.

## Notes

- Reference implementation: M1-444 (`ReEvaluationJob` + `ReEvaluationJobScheduledPathIT`).
  The pattern: `@Inject Clock clock = Clock.systemUTC();` (the initializer keeps
  hand-constructed test instances non-null; CDI injection overrides it),
  sample `clock.instant()` once per query/transaction, bind it; pin a fixed
  Clock in the IT via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`.
- The Clock producer is `ThrottledAdminNotifier.systemUtcClock()`
  (`@Produces @ApplicationScoped`, returns `Clock.systemUTC()`).
- Existing injected-`Clock` consumers to model after / verify already-correct:
  `DigestScheduler`, `RateCapBucket`, `ConfirmStateService`, `RelayHealthTracker`,
  `NostrStreamSource`, `ThrottledAdminNotifier` itself.
- The "who owns time — app or DB" question is real: this ticket deliberately
  does NOT migrate the whole schema's audit timestamps to the app clock. Only
  decision-logic comparisons (and the writes a component reads back for its own
  decisions) move. A blanket migration of all 23 DDL `DEFAULT now()` + 44 audit
  writes is explicitly out of scope and would be a separate architectural call.
- Expect decomposition: if the (A) set spans more than a handful of components,
  the plan-writer should recommend splitting into per-component child tickets so
  each lands as a small, reviewable, independently-verifiable diff.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-447-injectable-clock-decision-logic-sweep.md
```
