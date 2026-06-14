---
id: M1-339
title: "Stage1Pipeline: narrow the EscapedEntity suppression to the doc"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "Acceptance item 3 (class javadoc still renders entity examples as intended) is verified only by manual inspection of source escaping or javadoc generation; would be strengthened by naming the exact entity strings expected in source. Informational; non-blocking."
  blockers: []
blocked_by: []
files_budget: 1
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Stage 1 security pipeline logic — untouched; this ticket only changes the SuppressWarnings scope / javadoc entity escaping, not a single executable line.
  - The verbatim HTML-entity examples in the class javadoc — preserved (the reader must still see &amp;, &#105;gnore... rendered as intended).
acceptance:
  - "The class-level @SuppressWarnings(\"EscapedEntity\") on Stage1Pipeline no longer blankets every javadoc and string literal in the class. The legitimate verbatim HTML-entity examples live only in the class javadoc, so the suppression is removed in favor of escaping the entities at the source (e.g. &amp;amp; to render &amp; literally in javadoc output) so Error Prone's EscapedEntity check stays ACTIVE across the rest of the file. (If the developer judges the doc-escape form less readable, the alternative is a single tiny scoped member carrying the suppression — but escaping the doc and dropping the suppression entirely is preferred: no suppression, no silent passes.)"
  - "Error Prone's EscapedEntity check remains active for the implementation body of Stage1Pipeline (the load-bearing ingest security pipeline): a future method-level javadoc, error message, or string literal that accidentally writes &amp; where a literal ampersand-amp sequence was meant no longer compiles silently under a class-wide exemption."
  - "The class javadoc still renders the entity examples as intended (a reader sees &amp;, &#105;gnore...)."
  - "mvn -B clean verify from the repo root exits 0 (Error Prone passes with the narrowed scope)."
test_plan:
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
      files: 3
      added: 15
      removed: 16
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-339: Stage1Pipeline — narrow the EscapedEntity suppression

## Context

Deep-review v5.5 (opus-47, `06-module-infochat-collector.md` F3) found that
`Stage1Pipeline` carries a class-level `@SuppressWarnings("EscapedEntity")` whose
rationale ("the class javadoc documents HTML-entity examples verbatim") applies
only to the class javadoc block, but at the class declaration it silences the
check across every javadoc and string literal in the whole class.
**Verified at source 2026-06-14:** the suppression is at the class declaration
(Stage1Pipeline.java:175), above `public class Stage1Pipeline`, with the
entity-example comment immediately preceding it.

`Stage1Pipeline` is the load-bearing security pipeline for ingest. A blanket
class exemption hides exactly the kind of entity-escape mistake the check is
designed to catch in any future method javadoc, error message, or string literal.
This is a §1 surgical-changes smell: the suppression is wider than the surface it
protects.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Preferred (report Option A): escape the javadoc entities in source
  (`&amp;amp;` renders `&amp;`) and drop the suppression entirely — strictly
  safer, the check stays active. Option B (a one-field scoped suppression) is
  acceptable if the doc-escape reads worse.
