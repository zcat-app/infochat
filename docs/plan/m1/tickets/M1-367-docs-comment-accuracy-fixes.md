---
id: M1-367
title: "docs/comments: fix the Stage1RegexSet grammar, the EmbeddingMetadataDao 'defensive' framing, and the SimpleX mention-parser constant-time claim"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1RegexSet.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingMetadataDao.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Any code/behaviour change — this ticket edits javadoc/comments only; no logic, signatures, or tests change.
  - The Signal mention-parser comment — accurate as written (canonical 36-char UUIDs satisfy the equal-length precondition); only the SimpleX comment is corrected.
acceptance:
  - "Stage1RegexSet's RULE_DELIMITER_INJECTION javadoc is corrected from the malformed 'This rule catches pre-existing [REDACTED:...]-shaped placeholders is NOT this rule's job' to the intended sentence (Catching … is NOT this rule's job), so it reads as one grammatical clause."
  - "EmbeddingMetadataDao.readSingleton javadoc no longer labels the Optional.empty() SQL-boundary branch 'defensive' (which implies a §7 violation it is not); it names it as the legitimate SQL-deserialization empty case."
  - "SimpleXMentionParser's constant-time javadoc is narrowed to what holds: MessageDigest.isEqual is constant-time only within equal-length operands and short-circuits on a length mismatch; queue-address length is a protocol-fixed function of key size (not a secret), so the length-driven early return leaks nothing usable. (The Signal parser comment is left unchanged.)"
  - "mvn -B clean verify from the repo root exits 0 (comment-only change; no test additions expected)."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-367: comment/javadoc accuracy fixes

## Context

Three deep-review v6 comment-accuracy findings, grouped into one comment-only
ticket (avoids three one-line tickets):

- **opus-47 `06-module-infochat-collector.md` F9** (low) — `Stage1RegexSet`
  `RULE_DELIMITER_INJECTION` javadoc is grammatically broken (two glued
  sentences). **Verified at source 2026-06-14** (the file exists at the cited
  path; the malformed sentence is in the report quote). Security-boundary rule →
  the comment must be unambiguous.
- **opus-47 `06-module-infochat-collector.md` F10** (low) —
  `EmbeddingMetadataDao.readSingleton` javadoc calls a legitimate
  SQL-deserialization empty branch "defensive", implying the §7-forbidden shape
  it is not. **Verified at source 2026-06-14** (file present at cited path).
- **opus-48 `05-module-infochat-messaging-adapter.md` F2** (low) — the SimpleX
  mention-parser "constant-time / no byte-by-byte leak" javadoc overstates the
  guarantee: `MessageDigest.isEqual` short-circuits on length mismatch and
  SimpleX queue addresses are variable-length. **Verified per report**; the
  Signal parser (fixed 36-char UUIDs) is accurate and untouched. A security
  comment that asserts a stronger property than the code delivers is worse than
  none.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Comment-only across three files; the only acceptance gate beyond the wording is
  that `mvn verify` stays green.
