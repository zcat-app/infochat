---
id: M1-148
title: "MessagingAdapter SPI lifecycle (finalize→shutdown, start/stop) + low-level cleanup"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 10
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - capability-flag reconciliation / contract test (covered by M1-147)
  - the onMembershipEvent confirm-or-drop decision (covered by the M1-162 investigate-skeleton)
acceptance:
  - "MessagingAdapter SPI gains default void start()/stop() lifecycle methods; MessagingStartup stops using reflective Class.getMethod(\"start\") with catch(Throwable)"
  - "The finalize SPI method is renamed (shutdown/stop) so it no longer shadows Object.finalize()"
  - "SimpleXConfig is @Startup-validated (matching SignalConfig's eager validation); adapter startup probes use exponential backoff capped at the deadline; the SignalJsonRpcClient oversize-line drain is bulk-skip not per-char; the SimpleXAdapter handle table is bounded (LRU); findFirstString reads the known field instead of an attacker-influenced key search; HttpClient instances get connect timeouts"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-148: MessagingAdapter SPI lifecycle + low-level cleanup

## Context

Module-scoped `infochat-messaging-adapter` SPI-shape + low-level hygiene bundle:
the SPI lacks `start()`/`stop()` (so `MessagingStartup` uses reflective
dispatch with `catch(Throwable)`); the `finalize` SPI method shadows
`Object.finalize()`; `SimpleXConfig` validates lazily vs `SignalConfig`'s eager
`@Startup`; adapter startup probes busy-wait; the SignalJsonRpcClient oversize
drain is per-char; the SimpleXAdapter handle table grows unbounded;
`findFirstString` does an attacker-influenced key search; adapter `HttpClient`
instances lack connect timeouts.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-FINALIZE-SHADOW, §C-SPI-LIFECYCLE,
  §C-SIMPLEXCONFIG-LIFECYCLE, §C-ADAPTER-BACKOFF, §C-SIGNAL-DRAIN, §C-SIMPLEX-HANDLE-TABLE,
  §C-FINDFIRSTSTRING, §C-HTTPCLIENT-NOTIMEOUT; `opus-47-full-handout.md` §F-MAINT-41/47/50/51/85, F-PERF-02/11.
