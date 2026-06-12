---
id: M1-293
title: "Signal adapter: mention overflow, sourceName, capability values, launch classification"
status: done
created: 2026-06-11
last_updated: 2026-06-12
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
  - docs/design/06-messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Identity record shape (SPI) — displayName exists; only the Signal adapter's population of it changes.
  - SimpleX adapter (M1-294) and the jakarta.json pom declaration (M1-304).
  - The Provider-side rate limiter — only the adapter-declared capability values change.
  - Signal group-SPI feature surface beyond the mention-strip fix.
acceptance:
  - "U-10: SignalGroupHandler.stripBotMentions' span guard (~:212, 'start + length > body.length()' on attacker-controlled ints) is overflow-safe ((long) start + (long) length or equivalent); a named test feeds a message carrying a legitimate bot mention plus a hostile mention entry with start=Integer.MAX_VALUE,length=1 and asserts the message is processed and dispatched (today the wrapped-negative sum passes the guard, StringBuilder.delete throws, the dispatch-thread catch absorbs it, and the mention is silently dropped — per-message DoS of group functionality)."
  - "U-21: Signal inbound populates Identity.displayName from the envelope's sourceName on both the DM path (SignalJsonRpcClient ~:794) and the group path (SignalGroupHandler ~:174) — both currently pass null and no signal main-source file reads sourceName (verified 2026-06-11); design §6.5.3 specifies the field and SimpleX populates it; named tests assert a sourceName-bearing envelope yields a non-null displayName."
  - "U-31: SignalAdapter's capability values are reconciled with design §6.5.2 — maxSendsPerSecond 5 (code: 8) and minEditInterval 600ms (code: Duration.ZERO), both marked 'best-guess' in code — by adopting the design values, or amending the design with the measured rationale if investigation favors the code; either way design and code agree afterwards, and SignalAdapterSkeletonTest (which today pins the drifted 8/ZERO) is updated to pin the reconciled truth."
  - "U-20: the subprocess-launch IOException classification is decided: either launch failures route through supervised restart/backoff like SimpleX's restart loop, or the PERMANENT fail-fast is documented as deliberate in design §6.7 with rationale; one of the two, pinned by a named test or the design text."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 137
      removed: 22
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-12
    verdict: CLEAN
    base: main
    head: m1/M1-293-signal-adapter-mediums
    verdict_file: docs/plan/m1/redteam/M1-293-2026-06-12.md
    out_of_model_count: 0
    note: |
      Adversarial review of the committed branch tip (f2a61b67, pre-merge)
      against docs/spec/security.md. CLEAN: U-10 overflow fix, U-21
      sourceName→displayName (informational-only per D10), U-31 capability
      reconciliation, and U-20 launch-classification note open no gap
      between threat-model promises and the diff.
clarity_check:
  date: 2026-06-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-293: Signal adapter: mention overflow, sourceName, capability values, launch classification

## Context

Deep-review v5 verified **U-10** (MEDIUM, security — the run's only
input-validation bug), **U-21** (MEDIUM), **U-31** (MEDIUM), **U-20**
(MEDIUM, decision) (`deep-code-review/v5/UNIFIED-REPORT.md` §3; sources
`opus-47/05#F2` (U-10, unique), `fable-5/05#F4` + `gpt-55#M-12` (U-21),
`mimo/5#F1+F2` + `opus-47/05#F1` + `deepseek/05#F4` (U-31),
`deepseek/05#F2` (U-20, unique) — gitignored; all load-bearing facts
inlined; file:line anchors verified 2026-06-11).

Premise caveat carried from the report: deepseek fabricated capability
values in a side note (claimed Signal minEditInterval 100ms; actual ZERO) —
the U-31 values in acceptance are the verified ones (code 8/ZERO at
SignalAdapter:78/:81, design 5/600ms at design 06 ~:680/:686).

## Acceptance

See frontmatter. U-31 and U-20 carry investigate-then-pick forks; the diff
must pick one side and pin it (M1-280's Gate-4 item is the precedent).
Default directions: U-31 adopt the design values (M1-204 reconciled SimpleX
the same way); U-20 mirror SimpleX supervision (fail-fast-by-design needs a
written rationale to survive review).

## Out-of-scope

See frontmatter.

## Notes

- U-10 is one line of arithmetic plus a test; do not widen into general
  mention-shape validation (the surrounding guards already reject negative
  and out-of-range values — only the overflow case slips).
- U-21 threading: `sourceName` rides the existing envelope-decoding path
  (ReceivedDm and the group equivalent); no SPI change.
- Coordination: M1-285 also edits SignalJsonRpcClient (edit-fallback);
  M1-304 touches only the pom. Check the worktree landscape at start.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-293-*.md
```
