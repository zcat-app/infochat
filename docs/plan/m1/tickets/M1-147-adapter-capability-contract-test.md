---
id: M1-147
title: "Adapter capability-flag reconciliation + cross-adapter contract test (CT5)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - docs/design/06-messaging.md
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - SPI lifecycle (finalize→shutdown / start/stop) (covered by M1-148)
  - adapter resilience (handler isolation, hung-process) (covered by M1-132)
acceptance:
  - "A cross-adapter contract test suite asserts each adapter honours the same semantic-state classification: 'not connected' is one category (PERMANENT) across Signal and SimpleX, not TRANSIENT in one and PERMANENT in the other"
  - "Capability flags are reconciled against docs/design/06-messaging.md: supportsTypingIndicator and supportsCodeFormatting either match the design or the design is amended in the same commit (supportsTypingIndicator flips to false pending M1-105 verification)"
  - "Codec/encoder validators throw the SPI's checked MessagingException(PERMANENT), not IllegalStateException/IllegalArgumentException that bypass the two-category retry model"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/verification.md §Messaging
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-147: Adapter capability-flag reconciliation + cross-adapter contract test (CT5)

## Context

Adapter implementations inconsistently honour their own contracts: the same
semantic state classifies differently across adapters (`SignalAdapter`/`SimpleXAdapter`
disagree on "not connected" TRANSIENT-vs-PERMANENT), capability flags drift from
design notes (`supportsTypingIndicator`, `supportsCodeFormatting`,
`InMemoryAdapter.supportsCodeFormatting`), and codec validators throw exception
types that bypass the categorised retry model. A cross-adapter contract test is
the forcing function that prevents the next instance of this drift.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. Pick one classification per semantic state; align flags to
design (or amend design in the same commit).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A23, §C-ADAPTER-CLASSIFY,
  §C-CODEC-EXC, §C-CAPABILITY-DRIFT; `opus-47-full-handout.md` §F-MAINT-25/26/27, CT5;
  `opus-47-only-handout.md` §M9/10/11/19/30, CT5.
- Plan-writer pass recommended — touches the SPI, both production adapters, the
  in-memory adapter, the codec, and the design notes together.
