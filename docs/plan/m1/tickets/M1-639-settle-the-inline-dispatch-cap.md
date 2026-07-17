---
id: M1-639
title: "Settle the inline-dispatch cap exclusion and couple the dispatch knobs at boot"
status: done
created: 2026-07-17
last_updated: 2026-07-17
reviews:
  - round: 1
    date: 2026-07-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 98
      removed: 12
redteam_findings: []
redteam_audits:
  - date: 2026-07-17
    verdict: CLEAN
    base: 3b87558e
    head: working-tree (uncommitted impl on branch m1/M1-639-settle-the-inline-dispatch-cap)
    verdict_file: docs/plan/m1/redteam/M1-639-2026-07-17.md
    out_of_model_count: 1
    note: >-
      Pre-commit audit of the full working-tree diff: CLEAN, no findings. One
      out-of-model note: D61's structural preconditions (inline-on-transport
      classification of /retry --digest; per-minute-bucket metering) are
      process-enforced only — no test pins isInterruptible("/retry --digest")
      == false, so a future dispatch refactor could silently void the
      self-serialization bound. Candidate follow-up: a small classification
      pin test on InboundRouter.
clarity_check:
  date: 2026-07-17
  verdict: WARN
  warnings:
    - >-
      ACCEPTANCE-RUNNABLE item 3 hard-codes REFUSE-BOOT while §Notes decision
      (2) frames REFUSE-BOOT-vs-WARN as an open pick-at-start; resolve before
      implementation and edit item 3 if WARN is chosen. (Resolved at start
      2026-07-17: operator picked REFUSE BOOT — item 3 stands as written.
      Operator also confirmed decision (1): keep the exclusion, record D61.)
blocked_by: []
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Making /retry --digest interruptible or /stop-cancellable (reversing D35's
    inline/arrival-order classification). If the counting decision turns out to
    demand lifecycle changes of that shape, escalate — do not implement here.
  - >-
    Changing the VALUES of infochat.chat.dispatch.per-user-cap (2) or
    infochat.chat.dispatch.max-concurrency (4), or any semantics of the M1-636
    submit-time check for the interruptible class — its check order, reject
    text, and count derivation from the M1-638 registry are all settled.
  - >-
    A per-user fair scheduler (the 06-messaging.md §6.3.7 deferral stands
    regardless of how either decision here lands), and the colluding-identity
    residual the re-audit noted — multiple invited identities jointly filling
    the pool is a documented Sybil non-commitment, not this ticket's problem.
  - >-
    LlmRateCap / RateCapBucket values, windows, or bucket structure.
acceptance:
  - >-
    docs/spec/decisions.md gains a D61 entry recording whether inline
    LLM-triggering dispatch (/retry --digest, the class's only v1 member)
    counts against the M1-636 per-user cross-scope cap, with the bounding
    rationale stated (inline dispatch self-serializes to at most one
    concurrent call per adapter, takes no pool worker, and stays metered by
    the per-minute LLM bucket) — so the exclusion or inclusion is a durable
    decision, not a bare spec parenthetical.
  - >-
    docs/spec/security.md §Rate limiting's per-user-concurrency bullet
    cross-references the new D61 id where it states the /retry --digest
    exclusion, so the spec sentence is anchored to the decision record.
  - >-
    A test asserts the Provider REFUSES BOOT when
    infochat.chat.dispatch.per-user-cap >= infochat.chat.dispatch.max-concurrency,
    with an error naming both knobs — closing the misconfiguration the
    2026-07-17 re-audit flagged (cap >= pool silently voids the
    single-sender-cannot-fill-the-pool property) — and that a valid
    configuration (cap < pool) still initializes. Same code-enforced-coupling
    pattern as M1-610's reasoning-effort/max-tokens boot refusal.
  - >-
    The application.properties documentation block for the two dispatch knobs
    states the cap-must-be-below-pool coupling so an operator tuning either
    knob sees the constraint without reading InterruptibleDispatcher's source.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InterruptibleDispatcherValidationTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs:
  - D35
---

# M1-639: Settle the inline-dispatch cap exclusion and couple the dispatch knobs at boot

## Context

Both follow-ups from the M1-636 redteam audit
(`docs/plan/m1/redteam/M1-636-2026-07-17.md`, first pass FINDINGS with one
low, re-audit CLEAN with two out-of-model notes):

1. **The inline-dispatch exclusion is documented but not yet a decision.**
   M1-636's cap covers the D35 interruptible class; `/retry --digest` is
   deliberately outside it — D35 non-interruptible, dispatched inline on the
   transport thread, so it can never take a pool worker, self-serializes to
   at most one concurrent call, and stays metered by the per-minute bucket.
   The M1-636 in-branch remediation narrowed the security.md bullet to say
   exactly that, but the choice lives only as spec prose. It should be a
   decisions.md entry (D61) so future tickets touching the dispatch class
   (e.g. a second inline LLM surface) inherit a citable decision rather than
   re-deriving the reasoning — or overturn it deliberately.

2. **Nothing couples the two dispatch knobs at boot.** An operator setting
   `per-user-cap >= max-concurrency` silently voids the property the cap
   exists for — one sender can again occupy every pool worker. Operator
   config is trusted per the threat model, but the project already
   code-enforces exactly this footgun shape: `max-concurrency` refuses boot
   below 2, and M1-610 turned a reasoning-effort/max-tokens doc promise into
   a boot refusal. The re-audit re-flagged the gap so it would not be
   silently dropped.

## Notes

**Open decisions (resolve with the operator at `/m1-tick start`) — leans:**

- **(1) Lean: KEEP the exclusion; record it as D61.** Counting `/retry
  --digest` would require either an asymmetric check (the digest re-roll
  checks the sender's count but is itself never counted — it has no
  presence in the M1-638 registry — so digest-spam alone stays unbounded
  by it: an incoherent control) or expanding the turn-lifecycle registry to
  non-interruptible dispatch, which is real design work for a residual the
  audit itself rated bounded (+1 concurrent call, self-serialized,
  per-minute-metered, no pool worker). If the operator instead chooses to
  COUNT it, escalate → refine this ticket with the registry-expansion
  scope before implementing — that fork does not fit this ticket's sizing.
- **(2) Lean: REFUSE BOOT on `per-user-cap >= max-concurrency`** (the
  M1-610 precedent: couplings the docs promise become code-enforced). The
  softer alternative — boot WARN naming both knobs — preserves an
  operator's ability to deliberately run cap >= pool (i.e. opt out of the
  single-sender bound without editing source); pick at start. Whichever
  arm: validation lives beside the existing checks in
  `InterruptibleDispatcher.init()` (CDI-managed path only; `direct()`
  test instances bypass init, so hand-constructed router tests are
  unaffected), and the new test drives `init()` on hand-constructed
  instances — the existing `>= 2` check has no dedicated test, but this
  ticket adds coverage only for the NEW coupling (surgical-changes rule).

**Why files_budget 8 holds either lean:** decisions.md (1) + security.md
cross-ref (1) + InterruptibleDispatcher.java (1) + the new validation test
(1) + application.properties (1) = 5, plus ticket/STATUS bookkeeping.

**Sizing note.** Per the 2026-07-15 investigation-flow lesson, the
decision-recording half is deliberately paired with the small concrete
enforcement half so this ticket has a real code deliverable and fits the
standard cycle; it is not an investigation-only ticket.
