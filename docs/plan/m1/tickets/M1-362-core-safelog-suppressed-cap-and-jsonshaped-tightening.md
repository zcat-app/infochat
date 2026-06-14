---
id: M1-362
title: "core: cap per-node suppressed-throwable width in SafeLog; tighten isJsonShaped to reject trailing junk"
status: done
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 4
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java
  - infochat-core/src/test/java/app/zcat/infochat/core/log
  - infochat-core/src/test/java/app/zcat/infochat/core/audit
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The MAX_CAUSE_DEPTH cause-chain cap — unchanged; this adds the orthogonal per-node suppressed-WIDTH cap.
  - The "structural heuristic, not a full parse; server-side ?::jsonb is authoritative" contract of isJsonShaped — preserved; the change only rejects trailing non-whitespace after the balanced top-level token (the one shape the cast also rejects).
  - The Redactor catalogue / control-strip behaviour — unrelated.
acceptance:
  - "SafeLog.appendSuppressedClassNames caps emitted suppressed entries per node at a small constant (e.g. 5) and names the elision (a +Nmore token), mirroring the MAX_CAUSE_DEPTH truncate-and-name shape, so an adversarial throwable with thousands of suppressed cannot produce an unbounded log line."
  - "DefaultRedactionHook.isJsonShaped returns false for input with trailing non-whitespace after a balanced top-level {…}/[…] token (e.g. {\"a\":1}garbage), so off-contract input that the ?::jsonb cast would reject is caught by the fail-closed heuristic instead of aborting the audit transaction downstream."
  - "Tests pin: a throwable with > cap suppressed yields a bounded line with the +Nmore token; isJsonShaped rejects trailing junk and still accepts a clean balanced token followed by whitespace."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/log (suppressed-width cap test)
    - infochat-core/src/test/java/app/zcat/infochat/core/audit (trailing-junk rejection test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 90
      removed: 9
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "Acceptance item 1 leaves the suppressed-width cap as \"e.g. 5\" without fixing a constant; implementer will pin a value and the item-3 test pins to it. (Resolved at implementation: cap = 5, mirroring MAX_CAUSE_DEPTH.)"
    - "security_relevant: false is defensible but borderline given the isJsonShaped fix keeps the audit transaction fail-closed; flip to true if the team treats audit-transaction integrity as security-flagged."
  blockers: []
---

# M1-362: SafeLog suppressed-width cap + isJsonShaped tightening

## Context

Two deep-review v6 findings on `infochat-core`, grouped (both small fail-safe
tightenings):

- **opus-47 `02-module-infochat-core.md` F3** (low, PERFORMANCE) —
  `SafeLog.appendSuppressedClassNames` walks every suppressed entry per
  cause-chain node with no width cap; the cause chain is depth-capped but the
  suppressed list is not, so an adversarial throwable can produce an unbounded
  log line. **Verified at source 2026-06-14:** `SafeLog.java` exists at the cited
  path; the suppressed loop has no `Math.min`/cap.

- **opus-47 `02-module-infochat-core.md` F2** (low, SECURITY) — `isJsonShaped`
  accepts `{"a":1}garbage` (trailing non-whitespace after a balanced token),
  which the `?::jsonb` cast rejects, aborting the audit-before-effect
  transaction the function exists to keep fail-closed. **Verified at source
  2026-06-14:** the loop in `DefaultRedactionHook.isJsonShaped` (81-121) touches
  no counter on default-case chars and returns true after a balanced token
  regardless of trailing content.

**DISPUTE — flag for the implementer / user:** opus-48's core pass explicitly
*rejected* the isJsonShaped item as a non-finding, on the basis that the
narrower-than-a-parser behaviour is the documented deliberate contract and the
authoritative parse is the server-side cast. The SafeLog item is uncontested.
The isJsonShaped tightening is included here because the trailing-junk acceptance
can still abort an audit transaction (a real if low-probability fail path) and the
fix is ~2 lines — but if the team agrees with opus-48 that the documented contract
is sufficient, drop acceptance item 2 and keep this a SafeLog-only ticket.

## Acceptance / Out-of-scope

See frontmatter.
