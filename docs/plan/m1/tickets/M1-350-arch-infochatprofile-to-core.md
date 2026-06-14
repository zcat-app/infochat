---
id: M1-350
title: "arch: consolidate the duplicated InfochatProfile enum + Validator into infochat-core"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/config/InfochatProfile.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/config/InfochatProfile.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/config/InfochatProfile.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/config
  - infochat-provider/src/test/java/app/zcat/infochat/provider/config
  - docs/design/09-reference.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The four profile NAMES (laptop, vps, pi, remote-llm) and their semantics — unchanged; this moves the enum, it does not add/rename a profile.
  - The Validator bean's TEST/DEVELOPMENT carve-out logic — moved verbatim, not changed.
  - The application.properties profile blocks in either service — Quarkus profile names are config identifiers, not Java packages, so they do not move.
acceptance:
  - "A single InfochatProfile enum lives at infochat-core/src/main/java/app/zcat/infochat/core/config/InfochatProfile.java carrying the four constants (LAPTOP, VPS, PI, REMOTE_LLM), resolveOrThrow, and the @ApplicationScoped Validator inner bean, moved verbatim from the collector copy."
  - "Both per-service copies (collector/config/InfochatProfile.java and provider/config/InfochatProfile.java) are deleted; every import and reference across collector + provider main and test sources resolves to app.zcat.infochat.core.config.InfochatProfile."
  - "The pre-existing per-service InfochatProfileTest cases are repointed at the core type (relocated or import-updated) and stay green; the Validator's @ApplicationScoped bean is discovered in both services on boot with no behaviour change."
  - "docs/design/09-reference.md §9.1 lists InfochatProfile alongside the other infochat-core beans/types as the documented home (one-line addition to the existing 'deliberate Quarkus coupling' note)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/config (repoint InfochatProfileTest at the core type)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/config (repoint InfochatProfileTest at the core type)
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
      files: 5
      added: 4
      removed: 142
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-350: consolidate InfochatProfile into infochat-core

## Context

Deep-review v6 finding, **opus-47 `01-architecture.md` F1** (medium,
MAINTAINABILITY-RULES-DRIFT). The hardware-profile selector enum + its
`@ApplicationScoped Validator` startup bean is duplicated byte-for-byte across
Collector and Provider; the provider copy's own javadoc admits the drift
("This file is duplicated between Collector and Provider in v1 … the
duplication remains").

**Verified at source 2026-06-14:** both files exist —
`infochat-collector/src/main/java/app/zcat/infochat/collector/config/InfochatProfile.java`
and `infochat-provider/src/main/java/app/zcat/infochat/provider/config/InfochatProfile.java`.
Both services already depend on `infochat-core`, which is the documented home
for shared types (`docs/design/09-reference.md` §9.1) and already exposes peer
`@ApplicationScoped` beans (`AuditLogWriter`, `DefaultRedactionHook`,
`ThrottledAdminNotifier`). The risk the duplication invites is silent
profile-set drift: a fifth profile or a changed `resolveOrThrow` message added
to one copy and not the other.

opus-47 surfaced this; opus-48's architecture pass did not contradict it.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- One source of truth removes the drift surface entirely; the four names become
  a single import the build enforces.
- No new module edge is created — neither sibling library module needs the enum,
  and both services already pull `infochat-core`.
