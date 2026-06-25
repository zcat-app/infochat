---
id: M1-447
title: "Make decision-logic time injectable: classify all now() sites + convert security-timing trio"
status: done
created: 2026-06-24
last_updated: 2026-06-25
blocked_by: []
files_budget: 16
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-447.md
out_of_scope:
  - "The coding-style RULE text (docs/process/engineering-rules-verbatim.md, CLAUDE.md §Coding style) and the docs/process/reviewer-prompt.md check that enforces it. Those companion process changes (items 3–4 of the 2026-06-24 design discussion) land as separate `process:` commits — they are pure-doc and bypass the ticket flow — not in this code ticket. This ticket should be started AFTER the rule lands so the implementer converts against an agreed standard."
  - "(B) pure audit/record writes and Flyway DDL `DEFAULT now()` — left on the DB clock (the system-of-record convention). Converting them would diverge timestamp authorship from the rest of the schema for no testability gain. See acceptance item 3."
  - "ReEvaluationJob — already converted by M1-444 (the reference implementation). Do not re-touch it."
  - "Any behavioural change to a time window / threshold / cadence. This is a determinism refactor: with the real production Clock, behaviour is byte-for-byte preserved. Changing a window's size is a separate ticket."
  - "The other 8 unconverted (A) decision-logic components found by the classification audit are DEFERRED to follow-up tickets, NOT converted here: the 5 partition-scan workers (EmbeddingWorker, EntityExtractorWorker, TaggerWorker, ReadyPromoter, PerSourceUnknownTracker — SQL `now() - interval` → bound cutoff; EmbeddingWorker and EntityExtractorWorker additionally have existing ITs that a conversion would have to MODIFY), plus PartitionPruner (retention cutoff), DigestRetryService (retry cooldown), and FetchScheduler (kind-tick interval). This ticket converts ONLY the security-timing trio so the diff stays small and independently reviewable (ticket-body §Notes 'expect decomposition'). The committed audit doc enumerates these as the follow-up backlog."
acceptance:
  - "A committed classification audit at EXACTLY `docs/plan/m1/now-clock-audit.md` classifies every production current-time site (Java `Instant.now()` / `LocalDate.now()` / `OffsetDateTime.now()` / etc. and SQL `now()` / `current_timestamp` inside Java query strings, plus any Flyway DDL `DEFAULT now()`) into: (A) decision-logic time source — a comparison/gate that determines behaviour (scan windows, cooldowns, TTL/expiry checks, rate-limit windows, probation/ban/invite-expiry timing, retry/retention cutoffs) — vs (B) pure audit/record write — created_at/updated_at/status_changed_at stamps and DDL defaults never read back to gate a decision — vs (C) display/formatting. The audit lists every unconverted (A) component and marks each CONVERTED-IN-THIS-TICKET (the security-timing trio in the next item) vs DEFERRED-TO-FOLLOWUP. The classification is explicit and reviewable."
  - "The three in-scope security-timing components — InviteCodeConsumer (invite-expiry SQL gate, brute-force-attempt window, probation-window timing), GroupAutoPromoteService (probation-eligibility gate), AdminReviewTtlJob (quarantine-review TTL expiry gate) — each read their current instant from the injected `java.time.Clock` (the app-wide `@Produces @ApplicationScoped Clock` in `ThrottledAdminNotifier.systemUtcClock()`; test seam `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`), never inline `now()` / `Instant.now()`. Per the M1-444 rule, a component that reads back its own time-write for a decision moves wholesale to the one clock — no two-clock split (a value written by one clock and compared against another). Behaviour is byte-for-byte preserved under the real production Clock (`Clock.systemUTC()`)."
  - "(B) pure audit/record writes and DDL `DEFAULT now()` are LEFT on the DB clock and explicitly NOT converted. The other 8 unconverted (A) components (see out_of_scope) are NOT converted in this ticket."
  - "Each of the three converted components gains a NEW deterministic test that pins the Clock via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)` and asserts the time-gated behaviour at a fixed instant (named in `test_plan.adds`). All such tests are ADDITIVE — this ticket modifies the assertions of NO pre-existing test (there is no `test_plan.modifies`). The suite no longer depends on the wall-clock date for these three components — the time-bomb class M1-398 / M1-400 / M1-444 each fixed one instance of."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "InviteCodeConsumerClockIT — pins Clock.fixed(...) and asserts invite-expiry (expires_at gate), brute-force-attempt window counting, and probation-window timing are all decided against the injected instant (final IT/unit suffix settled at implementation per whether a DB is needed)."
    - "GroupAutoPromoteServiceClockIT — pins Clock.fixed(...) and asserts the probation-eligibility gate (probation_until vs the injected now) deterministically."
    - "AdminReviewTtlJobClockIT — pins Clock.fixed(...) and asserts the quarantine-review TTL auto-reject boundary against the injected instant."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 589
      removed: 21
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-25
    verdict: CLEAN
    base: f7c886cc7db0301d665e58ab0ed2ecfc2aed0660
    head: "working-tree (uncommitted on m1/M1-447-make-decision-logic-time-injec)"
    verdict_file: docs/plan/m1/redteam/M1-447-2026-06-25.md
    out_of_model_count: 0
    note: |
      In-progress audit after code-reviewer APPROVE, before commit. CLEAN — the
      injectable-Clock conversion preserves behaviour byte-for-byte under the
      production Clock.systemUTC(); no security promise weakened. Nothing feeds
      a remediation ticket.
revisions:
  - date: 2026-06-24
    reason: |
      clarity-fail refine. (1) Fixed the TEST-CHANGES-AUTHORIZED blocker: item-4
      tests are now ADDITIVE-only ("gains" a new test, never "extends" a
      pre-existing one), so no test_plan.modifies is needed. (2) Scoped the
      conversion to the security-timing trio (InviteCodeConsumer,
      GroupAutoPromoteService, AdminReviewTtlJob) after a classification recon
      found 11 unconverted (A) components (~23 files) — over files_budget:16 and
      too large for one reviewable diff; the trio has no existing time-tests, so
      the conversion is clean adds. The other 8 (A) components are now named in
      out_of_scope as follow-up backlog. (3) Named the audit artifact exactly
      (docs/plan/m1/now-clock-audit.md, dropped "or equivalent") and named the
      converted components + their new test classes (clearing both clarity
      WARNINGs).
    prior_acceptance_item_4: |
      "Each converted component gains or extends a test that pins the Clock to a
      fixed instant and asserts the time-gated behaviour deterministically
      (named per component in the diff), so the suite no longer depends on the
      wall-clock date — the time-bomb class M1-398 / M1-400 / M1-444 each fixed
      one instance of."
escalations:
  - date: 2026-06-24
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL — Acceptance item 4 says each converted
      component "gains or extends a test." The word "extends" contemplates
      modifying a pre-existing test, but test_plan has no `modifies:` key and
      all test names are deferred to "implementation time," so no pre-existing
      test is listed with its new expected behaviour. If "extends" is exercised,
      the modification is not authorized by the ticket. Fix: (a) add a
      test_plan.modifies: list enumerating which existing tests are extended and
      the new assertion, or (b) replace "gains or extends" with "gains" (new
      tests only) and capture any required extension as a modifies: entry.
clarity_check:
  date: 2026-06-24
  verdict: PASS
  warnings: []
  blockers: []
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

This ticket moves the *security-timing* production sites onto the
injectable-`Clock` pattern M1-444 established, after a classification recon
(2026-06-24) that sized the surface concretely. The raw surface is large (~90
Java `*.now()` matches + ~21 SQL `now()` files across both services) but the
**unconverted (A) decision-logic set is 11 components**; most other matches are
pure audit-timestamp writes / display formatting that legitimately stay on the
DB clock. 11 components (~23 files) exceeds `files_budget: 16` and is too large
for one reviewable diff, so this ticket is **scoped to the security-timing
trio** — `InviteCodeConsumer`, `GroupAutoPromoteService`, `AdminReviewTtlJob`
(invite-expiry / probation / quarantine-TTL gates; all `security_relevant`, and
none has an existing time-test, so the conversion is clean test *additions*).
The remaining 8 (A) components are named in `out_of_scope` as follow-up backlog.
The committed classification audit still covers the **whole** surface; only the
*conversion* is scoped. `complexity: high` reflects the full-surface audit plus
the cross-cutting trio conversion.

## Acceptance

See the YAML `acceptance:` list. In short: classify every production current-time
site (Java `*.now()` and SQL `now()` in query strings) into (A) decision-logic /
(B) pure-audit / (C) display in a committed `docs/plan/m1/now-clock-audit.md`;
convert ONLY the security-timing trio to an injected `Clock` (whole-component, no
two-clock split); leave (B) on the DB clock and the other 8 (A) components for
follow-up tickets; add a NEW fixed-Clock test per converted component (additive,
no pre-existing test modified); full suite green.

## Out-of-scope

See the YAML `out_of_scope:` list. The coding-style **rule** and the
**reviewer-prompt** check (items 3–4 of the plan) are pure-doc `process:`
commits done separately and have already landed (`§9`, commit 7521a279); this
ticket is the code sweep. (B) audit writes and DDL `DEFAULT now()` stay on the DB
clock. The other 8 unconverted (A) components (5 partition-scan workers,
`PartitionPruner`, `DigestRetryService`, `FetchScheduler`) are deferred to
follow-up tickets. `ReEvaluationJob` is already done. No time-window behaviour
changes.

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
