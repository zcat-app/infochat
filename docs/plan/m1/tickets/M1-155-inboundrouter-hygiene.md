---
id: M1-155
title: "InboundRouter hygiene (chat body-cap ordering, bidi-control gap, lookupGroupId Optional)"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by:
  - M1-125
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the reply-target / dup-name work (M1-125 — this serialises after it on the shared file)
  - the /stop scope fix (M1-138 — does not edit InboundRouter)
acceptance:
  - "The chat-mode body cap runs before the DB writes the spec forbids for oversized messages (currently after)"
  - "Bidi-control normalization covers U+061C, U+200E, U+200F (NFKC does not remove them) in both InboundRouter and Stage1Pipeline"
  - "lookupGroupId returns Optional<UUID> and the empty case is silent-dropped / specific-logged rather than throwing IllegalStateException (closing the timing oracle)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Ingest pipeline (security side)
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 227
      removed: 46
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: security_relevant is false but acceptance items 2 and 3 directly address security-spec invariants (bidi normalization in §Authorization model step 1.7 and timing-oracle closure on group lookup). Consider setting security_relevant: true so the redteam skill is triggered on this ticket."
  blockers: []
---

# M1-155: InboundRouter hygiene

## Context

Three `InboundRouter`-locus items: (C-BODYCAP-ORDER) the chat-mode body cap runs
after DB writes the spec forbids for oversized messages
(`InboundRouter.java:509-543`); (C-BIDI-GAP) normalization misses U+061C/U+200E/U+200F
(`InboundRouter.java:962-965`, `Stage1Pipeline.java:283-294`); (C-GROUPLOOKUP-THROW)
`lookupGroupId` throws on a missing group (`:740-756`), a weak timing oracle.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. `blocked_by: M1-125` — both edit `InboundRouter.java`; this is
the PROV-ROUTER lane, serialized after the reply-target fix.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-BODYCAP-ORDER, §C-BIDI-GAP,
  §C-GROUPLOOKUP-THROW; `opus-47-full-handout.md` §F-MAINT-38/66, F-SEC-24.
