---
id: M1-281
title: "Core contracts: contact-id redaction parity, Fetcher rename"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 16
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/log/ContactIds.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java
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
acceptance:
  - "ContactIds matches design §4.11 and V31 (6-char prefix + ellipsis + 4-char suffix, threshold 10; today Java does 8+'...'+4, threshold 16 — Java is the deviant); a parity IT asserts the Java helper and the V31 SQL function produce identical output for a shared fixture set including boundary lengths."
  - "Existing tests pinning the old 8+4/16 Java shape are updated to the design shape (authorized modification; enumerate them via grep before the diff)."
  - "The Fetcher SPI's dispatch token is renamed from sourceId to a name that no longer collides with source.id semantics (the report suggests dispatchKey) across the SPI, all implementations, and call sites; the javadoc sentence 'It is NOT the source.id UUID' attaches to a parameter whose name no longer invites the confusion."
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
