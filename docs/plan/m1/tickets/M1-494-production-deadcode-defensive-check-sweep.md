---
id: M1-494
title: "Production dead-code and defensive-check cleanup sweep"
status: pending
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 16
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Any behavioral change to a live code path; every item is dead-code removal, defensive-check removal at an internal (non-boundary) seam, or a contained micro-fix that preserves observable behavior."
  - "The render-timeout cancellation (13#F4) must preserve the existing degrade behavior; it only additionally cancels the orphaned future."
acceptance:
  - >-
    Each of the following is removed or corrected without changing observable
    production behavior, and the full suite stays green: (04#F1) redundant
    Objects.requireNonNull on NullAway-proven non-null params in
    PostPersister.java:116-117; (04#F2) array-of-one capture in
    Kind6Handler.java:137-168 replaced with TransactionHelper.inTransactionReturning;
    (08#F3) unused boolean return of dispatchMembership in SignalGroupHandler.java:299;
    (10#F1) test-only ChatAgent.handle() entry point (ChatAgent.java:205-208)
    removed or made test-visibility-only; (10#F3)
    BundleLoader.supportedLanguages() returns an unmodifiable view
    (BundleLoader.java:134-136); (11#F2) dead ExportPaginator.currentSize and its
    redundant render (ExportPaginator.java:42,63,68); (13#F2) dead successReplyText
    local (VouchCommandHandler.java:215); (13#F3) unreachable null-check on
    resolveContactId (UnsaveCommandHandler.java:76-79,122-126); (13#F5) unused
    SlotCoordinates.isDegraded (DigestRetryService.java:33,111-151); (15#F3)
    defensive null-checks against the non-null LlmResponse contract
    (SummaryProseGenerator.java:116-119).
  - >-
    (13#F4) DigestWorker cancels the orphaned LLM-render future on timeout
    (DigestWorker.java:172-185) rather than leaving it running; existing degrade
    behavior preserved.
  - >-
    (07#F2) the duplicated providerName() proxy-unwrap default lives once, shared
    by LlmProvider and EmbeddingProvider (LlmProvider.java:71-78,
    EmbeddingProvider.java:48-55).
  - "mvn -B verify is green from the repo root."
test_plan:
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

# M1-494: Production dead-code and defensive-check cleanup sweep

## Context

From `/deep-code-review full` (2026-06-27), a batch of low-severity production
findings — dead variables/returns, defensive checks at internal (non-boundary)
seams forbidden by engineering-rules §7, test-only entry points in production
code, and one contained micro-fix (orphaned-future cancel) —
verified at source and bundled as one sweep per the M1-462 precedent. Findings:
04#F1, 04#F2, 07#F2, 08#F3, 10#F1, 10#F3, 11#F2, 13#F2, 13#F3,
13#F4, 13#F5, 15#F3 (12 findings). (Two findings from the original report —
07#F1 silent body-cap and 14#F3 /help double query — were FALSIFIED as
documented-intentional behavior and dropped.) 10#F2 (no-arg
AssetCommandFamilyOracle ctor) was split out to M1-520 during a budget-breach
refine: its proper resolution (remove the ctor, which is coupled to the dead
`assetRegistry != null` guard and must move with it) ripples to ~9 cross-package
test call sites — not a contained micro-fix, and it would breach this sweep's
files_budget.

## Acceptance

See frontmatter — one behavior-preserving correction per finding, full suite
green. Most are deletions; 13#F4 (cancel orphaned future) is the only one with a
behavioral edge, scoped tightly.

## Out-of-scope

See frontmatter. No live-path behavior change; 13#F4 preserves the degrade path.

## Notes

- Source: `/deep-code-review full` (2026-06-27), the 13 listed findings.
- Per CLAUDE.md §No defensive code: the removed null-checks are between internal
  classes (NullAway-proven non-null), not at system boundaries.
- `TransactionHelper.inTransactionReturning` is the in-repo replacement for the
  04#F2 array-of-one idiom.
- Large file count by nature of a sweep; each edit is independently small. If the
  reviewer prefers, split along subsystem lines.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-494-*.md
```
