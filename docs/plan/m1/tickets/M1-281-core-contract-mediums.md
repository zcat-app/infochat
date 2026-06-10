---
id: M1-281
title: "Core contracts: contact-id redaction parity, Fetcher rename"
status: done
created: 2026-06-09
last_updated: 2026-06-10
blocked_by: []
files_budget: 28
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/ContactIds.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/StreamSource.java
  - infochat-core/src/test/java/app/zcat/infochat/core
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - V31's SQL redaction function — it matches design §4.11 and is the side being matched; no migration.
  - Fetcher semantics — pure parameter/javadoc rename; behavior byte-identical.
  - The design §4.11 text itself — code aligns to it, not vice versa.
  - NormalizedPost's sourceId record component — renaming the accessor ripples into outbox/PostPersister and every test asserting post.sourceId(); deliberate residual, candidate follow-up ticket.
acceptance:
  - "ContactIds matches design §4.11 and V31 (6-char prefix + ellipsis + 4-char suffix, threshold 10; today Java does 8+'...'+4, threshold 16 — Java is the deviant); a parity IT asserts the Java helper and the V31 SQL function produce identical output for a shared fixture set including boundary lengths."
  - "Existing tests pinning the old 8+4/16 Java shape are updated to the design shape (authorized modification; enumerate them via grep before the diff)."
  - "The ingest dispatch token is renamed from sourceId to dispatchKey across BOTH ingest SPIs (Fetcher.fetch and StreamSource.start — the same token with the same 'It is NOT the source.id UUID' javadoc), all implementations, the helper methods/fields the token threads through (RSS/Bluesky/Reddit parsers, PaginationSaturationTracker, StreamSourceSupervisor/Registration/DrainHandle, NostrStreamSource, NostrEvent), and test doubles/locals holding the token; every javadoc sentence 'It is NOT the source.id UUID' attaches to a parameter named dispatchKey. NOT renamed: NormalizedPost's sourceId record component (see out_of_scope), and UUID locals genuinely holding source.id row ids (FetchSchedulerFailureLadderTest.readLastFetchAt/readLastSuccessAt, FetchSchedulerLogRedactionTest) — those are correctly named."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
escalations:
  - date: 2026-06-10
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation enumeration sweep (acceptance item 2 / Notes
      sweep) sized the coherent rename surface at ~23 files vs
      files_budget: 16, and found the StreamSource SPI
      (infochat-core/src/main/java/app/zcat/infochat/core/ingest/StreamSource.java)
      declares the same sourceId dispatch-token parameter with the identical
      "It is NOT the source.id UUID" javadoc but sits outside files_scope.
revisions:
  - date: 2026-06-10
    reason: budget-breach rework
    snapshot:
      status: escalated
      files_budget: 16
      files_scope:
        - infochat-core/src/main/java/app/zcat/infochat/core/log/ContactIds.java
        - infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java
        - infochat-core/src/test/java/app/zcat/infochat/core
        - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream
        - infochat-collector/src/test/java/app/zcat/infochat/collector
      escalation_reason: budget-breach
      acceptance_item_3_at_snapshot: |
        The Fetcher SPI's dispatch token is renamed from sourceId to a name
        that no longer collides with source.id semantics (the report suggests
        dispatchKey) across the SPI, all implementations, and call sites; the
        javadoc sentence 'It is NOT the source.id UUID' attaches to a
        parameter whose name no longer invites the confusion.
    refine_summary: |
      Pre-diff enumeration counted 25 files for the coherent rename
      (ContactIds side 3; Fetcher SPI + 6 impls; parsers +
      PaginationSaturationTracker 4; StreamSource SPI + 5 stream classes;
      5 test files with doubles/locals) vs files_budget 16, and found
      StreamSource.java carries the identical sourceId param + javadoc but
      was outside files_scope. Refine: files_budget 16 → 28 (25 counted +
      cascade headroom), StreamSource.java added to files_scope, acceptance
      item 3 rewritten to name both SPIs, the threaded helpers, and the
      deliberate non-renames (NormalizedPost component; UUID source.id
      locals), NormalizedPost residual added to out_of_scope.
reviews:
  - round: 1
    date: 2026-06-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 26
      added: 330
      removed: 159
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-10
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: ContactIds redaction is a security-adjacent surface (log redaction at rate-limit and admin-bootstrap log points). The change is a tightening alignment fix, not a new vector, so security_relevant: false is acceptable. The reviewer should note the privacy-surface context when inspecting the diff."
  blockers: []
---

# M1-281: Core contracts: contact-id redaction parity, Fetcher rename

## Context

Deep-review v4 verified mediums **M-C2** and **M-C3**
(`deep-code-review/v4/UNIFIED-REPORT.md` §2; sources
`deep-code-review/v4/opus-47/02-module-infochat-core.md#F1`,
`deep-code-review/v4/fable5/02-module-infochat-core.md#F2`):

- **M-C2:** the contact-id redaction shape diverges between Java and SQL:
  `ContactIds` renders 8-prefix + `"..."` + 4-suffix with threshold 16,
  while `V31 redact_contact_id` renders 6 + `…` + 4 with threshold 10 — and
  V31 cites design §4.11, making the Java side the deviant. The same contact
  id redacts to two different strings depending on which layer logged it,
  defeating log correlation and making the redaction contract ambiguous.
- **M-C3:** the public ingest SPI names its dispatch token `sourceId`
  (`Fetcher.fetch(long sourceId, …)`) while its own javadoc warns "It is NOT
  the `source.id` UUID" — a name that actively invites the bug its javadoc
  warns about.

## Acceptance

See frontmatter. Both are mechanical alignments with a parity test as the
lasting artifact.

## Out-of-scope

See frontmatter — the SQL/design side is the reference, not a target.

## Notes

- ContactIds is used in log output across modules; shortening the visible
  prefix (8→6) and lowering the threshold (16→10) only ever *reduces*
  exposed bytes, so the change is safe in the privacy direction. Sweep for
  tests and log-assertion fixtures pinning the old shape (the
  behavior-reversal grep) — the test_plan authorizes those updates.
- The Fetcher rename is parameter-name-only (positional call sites
  unaffected); the sweep is for implementations, overrides, and javadoc
  references across collector fetchers and stream sources, plus any test
  doubles implementing the SPI.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-281-*.md
```
