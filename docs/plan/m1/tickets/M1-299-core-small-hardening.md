---
id: M1-299
title: "Core hardening smalls: notifier fallback throttle, probe outside lock, escaper allocation"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/main/java/app/zcat/infochat/core/startup/AbstractInstanceLockGuard.java
  - infochat-core/src/main/java/app/zcat/infochat/core/util/JsonEscaper.java
  - infochat-core/src/test/java/app/zcat/infochat/core
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The notifier's DB-backed throttle window mechanics — only the degraded-DB FALLBACK path gains a throttle.
  - The advisory-lock protocol and heartbeat cadence — only where the probe runs relative to the lock changes.
  - JsonEscaper's output bytes — must stay byte-identical (pinned by acceptance).
acceptance:
  - "U-59: ThrottledAdminNotifier's degraded-DB fallback WARN is throttled by an in-memory per-(key,window) AtomicLong window (today it logs one line per caller for the duration of a DB outage, violating the class's own per-(key,window) contract); a named test fires N fallback notifications inside one window and asserts one WARN."
  - "U-60: the held-session probe no longer blocks @PreDestroy: the connection reference is snapshotted under the lock and the blocking SELECT 1 (up to the 10s network timeout) runs outside it (today probeHeldSession holds connectionLock across the probe and @PreDestroy blocks on the same lock); a named test or the existing lock-guard tests pin the new shape."
  - "U-61: JsonEscaper emits C0 control escapes via nibble emission instead of allocating a Formatter per control byte on the attacker-influenced ingest path; a named test asserts byte-identical output to the current implementation across all 32 C0 controls plus the standard escapes."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
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

# M1-299: Core hardening smalls: notifier fallback throttle, probe outside lock, escaper allocation

## Context

Deep-review v5 verified **U-59** (MEDIUM, unique opus-47), **U-60** (LOW,
unique deepseek), **U-61** (LOW, unique opus-48)
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; sources `opus-47/02#F1`,
`deepseek/02#F1`, `opus-48/02#F1` — gitignored; all load-bearing facts
inlined; verified 2026-06-11: probeHeldSession lives in
AbstractInstanceLockGuard (core) with the collector InstanceLockGuard
calling it; JsonEscaper's Formatter use confirmed).

Three independent small fixes in infochat-core, bundled for one review.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- U-61's value is allocation pressure on a hostile-controlled path, not
  correctness — hence the byte-identical pin; if the outputs differ in any
  case the fix is wrong.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-299-*.md
```
