---
id: M1-274
title: "Capability flags: reconcile with design, prune speculative"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - docs/design/06-messaging.md
  - docs/spec/messaging.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - supportsCodeFormatting and supportsMarkdownLinks — load-bearing, consumed, spec-pinned (key conventions); untouched.
  - Implementing outbound chunking — the default cut deletes the unconsumed flag instead; implementing chunking is the refine path if the user overrules (see Notes).
  - Transport classification and start-race work (M1-273).
acceptance:
  - "maxMessageBytes is removed from the adapter capability surface (declared by three adapters, read by nothing — zero consumers verified by grep), and design 06 §6.3.4 / the SPI doc no longer commit to outbound chunking; the two documents agree with each other and with the code after the edit."
  - "The supportsMultilineCode value disagreement is resolved to one truth: design §6.5.2 amended to match the shipped SignalAdapter false (or the adapter corrected to true if investigation shows the design was right — whichever, design and code agree and a test pins the adapter's declared value)."
  - "The speculative zero-consumer flags (supportsMultilineCode, supportsAttachments, supportsThreading) are deleted from the SPI and design, or retained only if a production consumer is wired in this diff; no flag remains that nothing reads."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
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

# M1-274: Capability flags: reconcile with design, prune speculative

## Context

Deep-review v4 verified mediums **M-M5** and **M-M6**
(`deep-code-review/v4/UNIFIED-REPORT.md` §2; sources
`deep-code-review/v4/fable5/05-module-infochat-messaging-adapter.md#F3/#F7`,
`deep-code-review/v4/mimo/report.md` MSG-005, opus-47/48 architecture
reports #F4):

- **M-M5:** `maxMessageBytes` is declared by three adapters and read by
  nothing; outbound chunking is implemented nowhere. The SPI doc and design
  §6.3.4 disagree about who owns the limit. fable5 documents the
  disagreement in detail.
- **M-M6:** `supportsMultilineCode` is `false` in `SignalAdapter` vs `true`
  in design §6.5.2; `supportsMultilineCode`/`supportsAttachments`/
  `supportsThreading` have zero production consumers. They ARE
  design-documented, so deletion requires a design edit too.

## Acceptance

See frontmatter. The principle applied is the project's own no-speculative-
code rule: a capability flag nothing consumes is a feature flag for a feature
that doesn't exist.

## Out-of-scope

See frontmatter — the two consumed, spec-pinned flags are explicitly
protected.

## Notes

- **⚠ Product decision embedded (report: "needs a design decision first"):**
  the default cut written into acceptance is *delete + amend design* — the
  simplest form meeting the goal, and reversible (re-adding a flag when a
  consumer ships is cheap). If the user instead wants outbound chunking
  implemented in v1 (long digests on Signal may hit transport limits — check
  whether the existing outbound line-cap already covers this before arguing
  either way), say so at start and refine this ticket; do not implement
  chunking under this acceptance.
- Before deleting, grep test code for consumers of the three flags — test
  doubles declaring them are part of the sweep (call-site rule).
- docs/spec/messaging.md is in scope only if the capability table there
  lists the deleted flags; verify first — if untouched, the negative-space
  check will show it as intentional.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-274-*.md
```
