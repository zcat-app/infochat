---
id: M1-360
title: "messaging: reset subprocess consecutive-crash counters on healthy uptime; make the config-bean enablement gate honest; route outbound cap check through Utf8"
status: done
created: 2026-06-14
last_updated: 2026-06-14
reviews:
  - round: 1
    date: 2026-06-14
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 320
      removed: 48
  - round: 2
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 371
      removed: 52
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 10
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalConfig.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXConfig.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXOutboundChunker.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The maxRestarts cap value (5) and the backoff schedule — unchanged; this fixes WHEN the counter resets, not the cap.
  - The eager-@Startup vs lazy-validate decision for the config beans — the ticket makes the existing gate functional (Option A: optional config values validated at use); it does not remove @Startup.
  - SimpleXOutboundChunker.utf8Length returning a length (not a boolean) — only the requireWithinCap boolean decision is rerouted through Utf8; the length-returning helper is a larger refactor left out.
acceptance:
  - "SimpleXSubprocess.consecutiveCrashes and SignalSubprocess.restartAttempts are reset to zero once a process has run past a healthy-uptime threshold before its next crash, so 'consecutive' means 'without intervening healthy uptime' (design §6.4.6) rather than 'since process start / over the whole host lifetime'. A long-uptime daemon that crashes once does not climb monotonically toward maxRestarts."
  - "A test simulates a process that runs past the healthy threshold then exits, and asserts the consecutive-crash counter is back at 1 (reset then incremented), not N."
  - "SignalConfig and SimpleXConfig no longer fail boot before their @PostConstruct enablement gate runs: the @ConfigProperty-injected required keys are made genuinely optional (Optional<String>) and validated at use for the enabled adapter, so an inmemory- or single-adapter deployment that triggers CDI discovery does not throw NoSuchElementException in the constructor."
  - "SimpleXMessageCodec.requireWithinCap uses Utf8.exceedsByteLength for the boolean cap decision (early-exit, no getBytes(UTF_8) allocation), keeping an exact byte count only on the failure branch; SimpleXOutboundChunker's requireWithinCap-equivalent call is aligned where it makes the same boolean decision."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex (crash-counter reset + Utf8 cap test)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal (crash-counter reset + config-gate test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
escalations:
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — The ticket sets files_budget: 8, but the diff
      touches 10 non-lifecycle files. Membership is fine (all 10 match a
      files_scope entry) and there is no per-line scope drift; the overage is
      purely the numeric count — the two test-directory globs expand to 4
      actual test files, so the real total is 6 main + 4 test = 10, not 8.
      All other checks PASS (test-integrity, out-of-scope, negative-space,
      acceptance, spec-conformance).
revisions:
  - date: 2026-06-14
    reason: budget-breach refine (round 1 rework)
    change: |
      files_budget: 8 -> 10. The original budget counted the two
      test-directory globs in files_scope as 2 files; they expand to 4 actual
      test files (SignalConfigTest, SignalSubprocessTest, SimpleXMessageCodecTest,
      SimpleXSubprocessTest), so the true file total is 6 main + 4 test = 10.
      No files_scope, out_of_scope, or acceptance change — membership and
      per-line scope were both clean; only the numeric bound was an accounting
      step low. Implementation already green on the full suite at round 1.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-360: subprocess crash-counter reset + config-gate + Utf8 cap check

## Context

Three deep-review v6 findings on `infochat-messaging-adapter`, grouped as
adapter subprocess/config-lifecycle correctness + one co-located allocation
cleanup:

- **opus-47 F2** (medium) — subprocess consecutive-crash counters never reset on
  successful uptime. **Verified 2026-06-14:** `SimpleXSubprocess` initializes a
  method-local `consecutiveCrashes = 0` (line 187), increments on each crash
  (200, 269), never resets; `SignalSubprocess.restartAttempts` resets only inside
  `start()` (line 139) and otherwise climbs in `onProcessExit` (197-198). A
  healthy long-uptime daemon can latch FAILED after unrelated crashes spread over
  months — the opposite of "consecutive" (design §6.4.6).
- **opus-47 F5** (low) — `@ConfigProperty` constructor injection has no
  `defaultValue`, so the `@PostConstruct` enablement gate is futile if the jar is
  ever CDI-indexed (the constructor throws on the missing required key before the
  gate runs). **Verified per report:** `SignalConfig`/`SimpleXConfig` inject
  required keys positionally with no default.
- **opus-48 F3** (low, SIMPLIFICATION) — `SimpleXMessageCodec.requireWithinCap`
  allocates `getBytes(UTF_8)` to measure length while the module's single-source
  `Utf8.exceedsByteLength` does it allocation-free with early-exit.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- A conservative healthy-uptime threshold (30–60s) preserves the spec's
  "consecutive" intent without a new tunable; capture supervisor start/exit
  timestamps if `process.info().totalCpuDuration()` proves unreliable.

## Round 1 rework

Reviewer verdict: REWORK (SCOPE-DRIFT-CHECK FAIL — file-count overage only;
all other checks PASS). Single item:

1. The diff touches 10 non-lifecycle files but `files_budget: 8`. Membership
   is fine (all 10 match a `files_scope` entry) and there is no per-line scope
   drift; the overage is purely numeric — the two test-directory globs in
   `files_scope` expand to 4 actual test files (SignalConfigTest,
   SignalSubprocessTest, SimpleXMessageCodecTest, SimpleXSubprocessTest), so
   the real file total is 6 main + 4 test = 10, not the 8 the budget assumed
   (it counted the two test dirs as 2 files). Resolve by either narrowing the
   change set or raising `files_budget` via `escalate → refine`.
